import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

globalThis.location = { origin: 'http://reader.test:1122' }
globalThis.localStorage = { getItem: () => null }
globalThis.sessionStorage = {
  getItem() {
    throw new Error('Web access must not read saved pairing keys')
  },
}
const compile = async path => {
  const source = await readFile(new URL(path, import.meta.url), 'utf8')
  return ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText
}
const moduleUrl = source =>
  `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
const ajaxUrl = moduleUrl(
  (await compile('../src/api/axios.ts'))
    .replace("from 'axios'", `from '${import.meta.resolve('axios')}'`)
    .replace('import.meta.env.VITE_API', 'undefined'),
)
const { default: ajax } = await import(ajaxUrl)
const { default: API, setApiEntryPoint } = await import(
  moduleUrl(
    (await compile('../src/api/api.ts'))
      .replace("from './axios'", `from '${ajaxUrl}'`)
      .replace(
        "import { sourceMode } from '@/config/sourceContext';",
        "const sourceMode = { value: 'book' };",
      ),
  )
)
setApiEntryPoint('http://reader.test:1122/', 'ws://reader.test:1123/')

test('HTTP requests do not attach saved access keys', async () => {
  let request
  ajax.defaults.adapter = async config => {
    request = config
    return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
  }
  await API.getBookShelf()
  assert.equal(request.headers.has('Authorization'), false)
  assert.equal(request.url, 'getBookshelf')
})

test('images, progress beacons and sockets work without pairing keys', () => {
  const urls = []
  globalThis.WebSocket = class {
    constructor(url) {
      urls.push(url)
    }
  }
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { sendBeacon: url => urls.push(url) },
  })
  API.search(
    'test',
    () => {},
    () => {},
  )
  API.debug(
    'source',
    'test',
    () => {},
    () => {},
  )
  API.saveBookProgressWithBeacon({ name: 'test' })
  const cover = new URL(API.getProxyCoverUrl('https://image.test/cover.jpg'))
  const image = new URL(
    API.getProxyImageUrl('book', 'https://image.test/a.jpg', 600),
  )
  urls.push(cover.toString(), image.toString())
  for (const url of urls) {
    assert.equal(new URL(url).searchParams.has('access_token'), false)
  }
  assert.equal(urls.length, 5)
  assert.equal(cover.searchParams.get('path'), 'https://image.test/cover.jpg')
  assert.equal(image.searchParams.get('width'), '600')
  assert.equal(image.searchParams.get('url'), 'book')
})

import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

globalThis.location = { href: 'http://reader.test/vue/index.html' }
const values = new Map()
globalThis.sessionStorage = {
  getItem: key => values.get(key) ?? null,
  setItem: (key, value) => values.set(key, value),
  removeItem: key => values.delete(key),
}
const source = await readFile(
  new URL('../src/utils/webCredentials.ts', import.meta.url),
  'utf8',
)
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const { getWebAccessKey, saveWebAccessKey, withWebAccessKey } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
)

test('credentials are isolated by service origin and port', () => {
  saveWebAccessKey('http://first.test:1122/', 'first')
  assert.equal(getWebAccessKey('http://first.test:1122/getBookshelf'), 'first')
  assert.equal(getWebAccessKey('http://first.test:1123/'), '')
  assert.equal(getWebAccessKey('http://second.test:1122/'), '')
})

test('image beacon and socket URLs carry the selected HTTP service credential', () => {
  saveWebAccessKey('http://reader.test:1122/', 'test key')
  for (const target of [
    'http://reader.test:1122/image?path=a&width=100',
    'http://reader.test:1122/saveBookProgress',
    'ws://reader.test:1123/searchBook',
  ]) {
    assert.equal(
      new URL(
        withWebAccessKey(target, 'http://reader.test:1122/'),
      ).searchParams.get('access_token'),
      'test key',
    )
  }
})

test('reset replaces stale token and preserves image parameters', () => {
  saveWebAccessKey('http://reader.test/', 'new')
  const url = new URL(
    withWebAccessKey(
      'http://reader.test/image?path=a&access_token=old',
      'http://reader.test/',
    ),
  )
  assert.equal(url.searchParams.get('path'), 'a')
  assert.deepEqual(url.searchParams.getAll('access_token'), ['new'])
  saveWebAccessKey('http://reader.test/', '')
  assert.equal(
    new URL(
      withWebAccessKey(url.toString(), 'http://reader.test/'),
    ).searchParams.has('access_token'),
    false,
  )
})

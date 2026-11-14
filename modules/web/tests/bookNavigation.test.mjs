import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'
import { createMemoryHistory, createRouter } from 'vue-router'

const source = await readFile(
  new URL('../src/utils/bookNavigation.ts', import.meta.url),
  'utf8',
)
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const { returnToBookList } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
)

const createBookRouter = () =>
  createRouter({
    history: createMemoryHistory(),
    routes: [
      ['shelf', '/'],
      ['search', '/search'],
      ['explore', '/explore'],
      ['book-detail', '/book'],
      ['chapter', '/chapter'],
    ].map(([name, path]) => ({ name, path, component: {} })),
  })

test('return to shelf after reading and opening details does not reopen the reader', async () => {
  const router = createBookRouter()
  await router.push('/')
  await router.push('/chapter')
  await router.push({ name: 'book-detail', query: { from: 'shelf' } })

  await returnToBookList(router, 'shelf')

  assert.equal(router.currentRoute.value.name, 'shelf')
  assert.equal(router.currentRoute.value.fullPath, '/')
})

for (const from of ['search', 'explore']) {
  test(`return to ${from} follows the label even when the previous page is the reader`, async () => {
    const router = createBookRouter()
    await router.push({ name: from })
    await router.push('/book')
    await router.push('/chapter')
    await router.push({ name: 'book-detail', query: { from } })

    await returnToBookList(router, from)

    assert.equal(router.currentRoute.value.name, from)
  })
}

test('details opened directly can return to shelf without previous history', async () => {
  const router = createBookRouter()
  await router.push('/book')

  await returnToBookList(router, 'shelf')

  assert.equal(router.currentRoute.value.name, 'shelf')
})

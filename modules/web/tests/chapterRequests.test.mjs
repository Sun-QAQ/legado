import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(
  new URL('../src/utils/chapterRequests.ts', import.meta.url),
  'utf8',
)
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const { createChapterRequests } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
)

test('chapter replacement cancels old request and ignores its late completion', () => {
  const requests = createChapterRequests()
  const first = requests.start('book', 0, true)
  const second = requests.start('book', 1, true)
  assert.equal(first.signal.aborted, true)
  assert.equal(first.isCurrent(), false)
  first.finish()
  assert.equal(requests.pending, 1)
  assert.equal(second.isCurrent(), true)
  second.finish()
  assert.equal(requests.pending, 0)
})

test('infinite loading coalesces duplicate pending chapters', () => {
  const requests = createChapterRequests()
  const first = requests.start('book', 1, false)
  assert.equal(requests.start('book', 1, false), undefined)
  assert.equal(requests.pending, 1)
  first.finish()
  assert.ok(requests.start('book', 1, false))
})

test('old completion cannot remove a newer request for the same chapter', () => {
  const requests = createChapterRequests()
  const old = requests.start('book', 0, true)
  const current = requests.start('book', 0, true)
  old.finish()
  assert.equal(requests.pending, 1)
  assert.equal(current.isCurrent(), true)
})

test('changing book aborts pending requests', () => {
  const requests = createChapterRequests()
  const first = requests.start('first', 0, false)
  requests.reset()
  const second = requests.start('second', 0, false)
  assert.equal(first.isCurrent(), false)
  assert.equal(first.signal.aborted, true)
  assert.equal(second.isCurrent(), true)
})

test('unmount cancels all requests and prevents further work', () => {
  const requests = createChapterRequests()
  const first = requests.start('book', 1, false)
  const second = requests.start('book', 2, false)
  requests.dispose()
  assert.equal(requests.pending, 0)
  assert.equal(first.signal.aborted, true)
  assert.equal(second.signal.aborted, true)
  assert.equal(second.isCurrent(), false)
  assert.equal(requests.start('book', 3, true), undefined)
})

import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'
const source = await readFile(
  new URL('../src/utils/chatState.ts', import.meta.url),
  'utf8',
)
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const { mergeChatState } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
)
const state = {
  conversationId: 'chat',
  revision: 'server:1',
  waiting: true,
  messages: [
    { id: 'old', text: 'history' },
    { id: 'reply', text: 'first' },
  ],
}

test('delta preserves history objects and appends in server order', () => {
  const result = mergeChatState(state, {
    ...state,
    revision: 'server:2',
    baseRevision: 'server:1',
    reset: false,
    messages: [
      { id: 'reply', text: 'second' },
      { id: 'new', text: 'new' },
    ],
  })
  assert.deepEqual(
    result.messages.map(message => message.text),
    ['history', 'second', 'new'],
  )
  assert.equal(result.messages[0], state.messages[0])
})

test('mismatched base and conversation request a full snapshot without modifying current state', () => {
  for (const change of [
    { baseRevision: 'server:0' },
    { conversationId: 'other' },
  ]) {
    assert.equal(
      mergeChatState(state, {
        ...state,
        reset: false,
        baseRevision: state.revision,
        messages: [],
        ...change,
      }),
      null,
    )
  }
  assert.equal(state.messages.length, 2)
})

test('full reset replaces deleted messages and supports older servers', () => {
  const full = { ...state, reset: true, revision: 'server:3', messages: [] }
  assert.equal(mergeChatState(state, full), full)
  const legacy = {
    ...state,
    revision: undefined,
    reset: undefined,
    messages: [{ text: 'legacy' }],
  }
  assert.equal(mergeChatState(state, legacy), legacy)
})

test('metadata-only completion retains history and missing message IDs trigger recovery', () => {
  const delta = {
    ...state,
    revision: 'server:2',
    baseRevision: state.revision,
    reset: false,
    waiting: false,
    messages: [],
  }
  const result = mergeChatState(state, delta)
  assert.equal(result.waiting, false)
  assert.deepEqual(result.messages, state.messages)
  assert.equal(
    mergeChatState(state, { ...delta, messages: [{ text: 'missing ID' }] }),
    null,
  )
})

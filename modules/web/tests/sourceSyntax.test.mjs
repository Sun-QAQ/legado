import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(
  new URL('../src/utils/sourceSyntax.ts', import.meta.url),
  'utf8',
)
const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2022,
  },
})
const { tokenizeSource, sourceLanguageForField, MAX_SYNTAX_TOKENS } =
  await import(
    `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
  )
const parts = (text, language) =>
  tokenizeSource(text, language).map(t => [text.slice(t.start, t.end), t.kind])
const values = (tokens, kind) =>
  tokens.filter(t => t[1] === kind).map(t => t[0])

test('strings, templates and comments shield embedded syntax', () => {
  const tokens = parts(
    'const a = "if @js: //"; /* return 42 */ let b = `true\n@@`; // const\nreturn 0x2a;',
    'javascript',
  )
  assert.deepEqual(values(tokens, 'keyword'), ['const', 'let', 'return'])
  assert.equal(values(tokens, 'comment').length, 2)
  assert.equal(values(tokens, 'string').length, 2)
  assert.deepEqual(values(tokens, 'number'), ['0x2a'])
  assert.deepEqual(values(tokens, 'rule'), [])
})
test('JSON keys, literals and exponent numbers', () => {
  const tokens = parts(
    '{"中文-key": "const // @js:", "enabled": true, "count": 1.5e-2}',
    'json',
  )
  assert.ok(values(tokens, 'property').includes('"中文-key"'))
  assert.deepEqual(values(tokens, 'keyword'), ['true'])
  assert.deepEqual(values(tokens, 'number'), ['1.5e-2'])
  assert.deepEqual(values(tokens, 'comment'), [])
  assert.deepEqual(values(tokens, 'rule'), [])
})
test('URLs and XPath are not JS comments', () => {
  const tokens = parts(
    'https://example.invalid/{{key}} @XPath://div/@class || @css:span @@ text',
  )
  assert.deepEqual(values(tokens, 'comment'), [])
  assert.deepEqual(values(tokens, 'rule'), [
    '{{',
    '}}',
    '@XPath:',
    '||',
    '@css:',
    '@@',
  ])
})
test('nested interpolation and JS tags return to rule mode', () => {
  const tokens = parts(
    "{{({a: '}}', b: {n: 1}}).b}} @css:div <js>let n = 2; /* </js> */</JS> @@text",
  )
  assert.deepEqual(values(tokens, 'rule'), [
    '{{',
    '}}',
    '@css:',
    '<js>',
    '</JS>',
    '@@',
  ])
  assert.deepEqual(values(tokens, 'keyword'), ['let'])
})
test('regular expressions protect slashes and distinguish division', () => {
  const tokens = parts(
    "const r = /[//]a\\/b/g; const n = 8 / 2; return /if/.test('a');",
    'javascript',
  )
  assert.ok(values(tokens, 'string').includes('/[//]a\\/b/g'))
  assert.ok(values(tokens, 'string').includes('/if/'))
  assert.deepEqual(values(tokens, 'comment'), [])
  assert.ok(values(tokens, 'operator').includes('/'))
})
test('unfinished input produces ordered valid ranges and recovers after newline', () => {
  for (const text of [
    "@js:'unfinished\nreturn 2",
    '@js:/* unfinished',
    '@js:`unfinished',
    '@js:"escaped\\',
  ]) {
    const tokens = tokenizeSource(text)
    tokens.forEach((t, i) => {
      assert.ok(t.start >= 0 && t.end > t.start && t.end <= text.length)
      if (i) assert.ok(tokens[i - 1].end <= t.start)
    })
  }
  assert.ok(
    values(parts("@js:'unfinished\nreturn 2"), 'keyword').includes('return'),
  )
})
test('long input highlights beyond 1024 characters and bounds token count', () => {
  assert.ok(
    values(
      parts(' '.repeat(4096) + '@js:const count = 42;'),
      'keyword',
    ).includes('const'),
  )
  assert.equal(
    tokenizeSource('let n = 1;\n'.repeat(20000), 'javascript').length,
    MAX_SYNTAX_TOKENS,
  )
})
test('field modes support scripts, JSON, rules and plain text', () => {
  assert.equal(sourceLanguageForField('loginCheckJs'), 'javascript')
  assert.equal(sourceLanguageForField('jsLib'), 'javascript')
  assert.equal(sourceLanguageForField('imageDecode'), 'javascript')
  assert.equal(sourceLanguageForField('header'), 'json')
  assert.equal(sourceLanguageForField('bookSourceName'), 'plain')
  assert.equal(sourceLanguageForField('ruleContent'), 'rule')
  assert.equal(sourceLanguageForField('chapterName'), 'rule')
  assert.deepEqual(parts('const 42', 'plain'), [])
  assert.deepEqual(values(parts('@js:return true', 'json'), 'keyword'), [
    'return',
    'true',
  ])
})

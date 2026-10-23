export type SourceLanguage = 'rule' | 'javascript' | 'json' | 'plain'
export type TokenKind =
  'keyword' | 'string' | 'comment' | 'number' | 'rule' | 'property' | 'operator'
export type SyntaxToken = { start: number; end: number; kind: TokenKind }

export const MAX_SYNTAX_TOKENS = 12000
const keywords = new Set(
  (
    'var let const function return if else for while do switch case break continue ' +
    'default try catch finally throw new this typeof instanceof in of delete void ' +
    'class extends super static import export from as async await yield true false null undefined'
  ).split(' '),
)
const expressionKeywords = new Set(
  'return throw case typeof void delete new yield await in of'.split(' '),
)
const markers = [
  '@xpath:',
  '@json:',
  '@css:',
  '@get:',
  '@put:',
  '@@',
  '&&',
  '||',
  '%%',
  '##',
]
const numberPattern =
  /(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)/y
const identifierStart = /[\p{L}_$]/u
const identifierPart = /[\p{L}\p{N}_$]/u
const plainFields = new Set([
  'bookSourceName',
  'bookSourceGroup',
  'bookSourceComment',
  'sourceName',
  'sourceGroup',
  'sourceComment',
  'variableComment',
  'concurrentRate',
  'bookUrlPattern',
  'style',
  'checkKeyWord',
])

export function sourceLanguageForField(key: string): SourceLanguage {
  if (/Js$/i.test(key) || key === 'jsLib' || key === 'imageDecode')
    return 'javascript'
  if (key === 'header' || key === 'loginUi') return 'json'
  if (plainFields.has(key)) return 'plain'
  return 'rule'
}

function quotedEnd(text: string, start: number, quote: string): number {
  let end = start + 1
  while (end < text.length) {
    const c = text[end++]
    if (c === '\\') end = Math.min(end + 1, text.length)
    else if (c === quote) break
    else if ((c === '\n' || c === '\r') && quote !== '`') {
      end--
      break
    }
  }
  return end
}

function regexEnd(text: string, start: number): number {
  let end = start + 1
  let inClass = false
  while (end < text.length) {
    const c = text[end++]
    if (c === '\n' || c === '\r') return start + 1
    if (c === '\\') end = Math.min(end + 1, text.length)
    else if (c === '[') inClass = true
    else if (c === ']') inClass = false
    else if (c === '/' && !inClass) {
      while (end < text.length && /[a-z]/i.test(text[end])) end++
      return end
    }
  }
  return start + 1
}

/** Tolerant display lexer. Tokens never overlap and source text is never evaluated. */
export function tokenizeSource(
  text: string,
  language: SourceLanguage = 'rule',
): SyntaxToken[] {
  if (language === 'plain') return []
  const tokens: SyntaxToken[] = []
  let index = 0
  let javascript = language === 'javascript'
  let closing = ''
  let braces = 0
  let regexAllowed = true
  const add = (end: number, kind: TokenKind) => {
    tokens.push({ start: index, end, kind })
    index = end
  }
  while (index < text.length && tokens.length < MAX_SYNTAX_TOKENS) {
    const c = text[index]
    if (/\s/.test(c)) {
      index++
      continue
    }
    const head = text.slice(index, index + 8).toLowerCase()
    if (javascript && closing && braces === 0 && head.startsWith(closing)) {
      add(index + closing.length, 'rule')
      javascript = false
      closing = ''
      continue
    }
    if (!javascript) {
      const jsMarker = ['@js:', '<js>', '{{'].find(marker =>
        head.startsWith(marker),
      )
      if (jsMarker) {
        closing = jsMarker === '<js>' ? '</js>' : jsMarker === '{{' ? '}}' : ''
        javascript = true
        regexAllowed = true
        braces = 0
        add(index + jsMarker.length, 'rule')
        continue
      }
      const marker = markers.find(marker => head.startsWith(marker))
      if (marker) {
        add(index + marker.length, 'rule')
        continue
      }
    }
    if (c === '"' || c === "'" || (javascript && c === '`')) {
      const end = quotedEnd(text, index, c)
      let next = end
      while (next < text.length && /\s/.test(text[next])) next++
      add(end, text[next] === ':' ? 'property' : 'string')
      regexAllowed = false
      continue
    }
    if (javascript && text.startsWith('//', index)) {
      const end = text.indexOf('\n', index)
      add(end < 0 ? text.length : end, 'comment')
      continue
    }
    if (javascript && text.startsWith('/*', index)) {
      const end = text.indexOf('*/', index + 2)
      add(end < 0 ? text.length : end + 2, 'comment')
      continue
    }
    if (javascript && c === '/' && regexAllowed) {
      const end = regexEnd(text, index)
      if (end > index + 1) {
        add(end, 'string')
        regexAllowed = false
        continue
      }
    }
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(text[index + 1] ?? ''))) {
      numberPattern.lastIndex = index
      const match = numberPattern.exec(text)
      if (match) {
        add(numberPattern.lastIndex, 'number')
        regexAllowed = false
        continue
      }
    }
    if (identifierStart.test(c)) {
      let end = index + 1
      while (end < text.length && identifierPart.test(text[end])) end++
      const word = text.slice(index, end)
      if (
        (javascript && keywords.has(word)) ||
        (language === 'json' && ['true', 'false', 'null'].includes(word))
      )
        add(end, 'keyword')
      else index = end
      regexAllowed = expressionKeywords.has(word)
      continue
    }
    if (javascript) {
      if (c === '{') braces++
      if (c === '}' && braces > 0) braces--
      if ('{}[]():;,.=+-*%<>!&|?~^/'.includes(c)) {
        add(index + 1, 'operator')
        regexAllowed = !')]}.'.includes(c)
        continue
      }
    } else if ('{}[]():,'.includes(c)) {
      add(index + 1, 'operator')
      continue
    }
    index++
  }
  return tokens
}

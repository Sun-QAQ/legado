export interface ChatMessagePart {
  code: boolean
  text: string
  imageUrl?: string
}

// Only create image elements for complete Markdown images with HTTP(S) URLs.
// Everything else remains Vue text, including HTML and unfinished stream chunks.
const imageParts = (text: string): ChatMessagePart[] => {
  const parts: ChatMessagePart[] = []
  const starts = /!\[((?:\\.|[^\]\\])*)\]\(/g
  let consumed = 0
  for (const match of text.matchAll(starts)) {
    if (match.index < consumed) continue
    const start = match.index + match[0].length
    let end = start
    let depth = 1
    for (; end < text.length; end++) {
      if (text[end] === '\\') {
        end++
        continue
      }
      if (text[end] === '(') depth++
      if (text[end] === ')' && --depth === 0) break
    }
    if (depth !== 0) continue
    const destination = text.slice(start, end).trim()
    const urlMatch = /^(?:<([^<>\r\n]+)>|(\S+?))(?:\s+["'][\s\S]*["'])?$/.exec(
      destination,
    )
    if (!urlMatch) continue
    const url = (urlMatch[1] || urlMatch[2]).replace(/\\([\\()[\]])/g, '$1')
    try {
      if (!['http:', 'https:'].includes(new URL(url).protocol)) continue
    } catch {
      continue
    }
    if (match.index > consumed)
      parts.push({ code: false, text: text.slice(consumed, match.index) })
    parts.push({
      code: false,
      text: match[1].replace(/\\([\\[\]])/g, '$1'),
      imageUrl: url,
    })
    consumed = end + 1
  }
  if (consumed < text.length)
    parts.push({ code: false, text: text.slice(consumed) })
  return parts
}

export const chatMessageParts = (text: string): ChatMessagePart[] =>
  text
    .split(/```(?:[^\n]*\n)?([\s\S]*?)(?:```|$)/g)
    .flatMap((part, index) =>
      index % 2 === 1 ? [{ code: true, text: part }] : imageParts(part),
    )

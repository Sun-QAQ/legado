export type MangaPage = { src: string; chapterPos: number }

// 与正文渲染一致：每张图片计为一个字符，段落之间计一个换行。
const imagePattern = /<img[^>]*src=['"]([^'"]*(?:['"][^>]+\})?)['"][^>]*>/gi

export const getMangaPages = (contents: string[]): MangaPage[] => {
  const pages: MangaPage[] = []
  let paragraphStart = 0
  for (const content of contents) {
    for (const match of content.matchAll(imagePattern)) {
      const prefix = content.slice(0, match.index).replace(imagePattern, ' ')
      pages.push({
        src: match[1],
        chapterPos: paragraphStart + prefix.length + 1,
      })
    }
    paragraphStart += content.replace(imagePattern, ' ').length + 1
  }
  return pages
}

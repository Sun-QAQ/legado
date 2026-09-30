import type { Book } from '@/book'
// 以章节数估算阅读进度，不伪造字数进度。
export const bookProgress = (book: Book) => {
  if (!book.totalChapterNum || !book.durChapterTime) return 0
  return Math.min(
    100,
    Math.max(
      0,
      Math.round(((book.durChapterIndex + 1) / book.totalChapterNum) * 100),
    ),
  )
}

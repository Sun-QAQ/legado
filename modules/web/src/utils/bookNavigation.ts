import type { Book, SeachBook } from '@/book'
import type { Router } from 'vue-router'

export type DetailBook = Book | SeachBook
const detailKey = 'web-book-detail'

export const openBookDetail = (
  router: Router,
  book: DetailBook,
  from: string,
) => {
  sessionStorage.setItem(detailKey, JSON.stringify(book))
  return router.push({
    name: 'book-detail',
    query: { url: book.bookUrl, origin: book.origin, from },
  })
}

export const restoreDetailBook = (url: string, origin: string) => {
  try {
    const book = JSON.parse(sessionStorage.getItem(detailKey) || 'null')
    if (
      book?.bookUrl === url &&
      book.origin === origin &&
      typeof book.name === 'string' &&
      typeof book.author === 'string'
    )
      return book as DetailBook
  } catch {
    /* 无效的旧缓存交由详情页显示恢复入口。 */
  }
}

export const startBookReading = (router: Router, book: Book) => {
  const reading = {
    bookUrl: book.bookUrl,
    name: book.name,
    author: book.author || '',
    chapterIndex: book.durChapterIndex || 0,
    chapterPos: book.durChapterPos || 0,
    isSeachBook: false,
  }
  for (const [key, value] of Object.entries({
    bookUrl: reading.bookUrl,
    bookName: reading.name,
    bookAuthor: reading.author,
    chapterIndex: reading.chapterIndex,
    chapterPos: reading.chapterPos,
    isSeachBook: reading.isSeachBook,
  }))
    sessionStorage.setItem(key, String(value))
  localStorage.setItem('readingRecent', JSON.stringify(reading))
  return router.push('/chapter')
}

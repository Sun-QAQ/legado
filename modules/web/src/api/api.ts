/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/api */
/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/web */

import type { webReadConfig } from '@/web'
import ajax from './axios'
import { sourceMode } from '@/config/sourceContext'
import type {
  BaseBook,
  Book,
  BookChapter,
  BookProgress,
  SeachBook,
} from '@/book'
import type { Source } from '@/source'

export type LeagdoApiResponse<T> = {
  isSuccess: boolean
  errorMsg: string
  data: T
}

export let legado_http_entry_point = ''
export let legado_webSocket_entry_point = ''

let wsOnError: typeof WebSocket.prototype.onerror = () => {}
let wsOnMessage: typeof WebSocket.prototype.onmessage = () => {}
export const setWebsocketOnMessage = (callback: typeof wsOnMessage) =>
  (wsOnMessage = callback)
export const setWebsocketOnError = (callback: typeof wsOnError) => {
  //WebSocket.prototype.onerror = callback
  wsOnError = callback
}

export const setApiEntryPoint = (
  http_entry_point: string,
  webSocket_entry_point: string,
) => {
  legado_http_entry_point = new URL(http_entry_point).toString()
  legado_webSocket_entry_point = new URL(webSocket_entry_point).toString()
  ajax.defaults.baseURL = legado_http_entry_point
}

// 书架API
// Http
const getReadConfig = async (http_url = legado_http_entry_point) => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('getReadConfig', {
    baseURL: http_url.toString(),
    timeout: 10000,
  })
  if (data.isSuccess) {
    try {
      return JSON.parse(data.data) as webReadConfig
    } catch {}
  }
}
const saveReadConfig = (config: webReadConfig) => {
  const settings = { ...config }
  delete settings.appAccentColor
  return ajax.post<LeagdoApiResponse<string>>('saveReadConfig', settings)
}

/** @deprecated: 使用`API.saveBookProgressWithBeacon`以确保在页面或者直接关闭的情况下保存进度 */
const saveBookProgress = (bookProgress: BookProgress) =>
  ajax.post('saveBookProgress', bookProgress)

/**主要在直接关闭浏览器情况下可靠发送书籍进度 */
const saveBookProgressWithBeacon = (bookProgress: BookProgress) => {
  if (!bookProgress) return
  // 常规请求可能会被取消 使用Fetch keep-alive 或者 navigator.sendBeacon
  navigator.sendBeacon(
    new URL('saveBookProgress', legado_http_entry_point),
    JSON.stringify(bookProgress),
  )
}

const getBookShelf = () => ajax.get<LeagdoApiResponse<Book[]>>('getBookshelf')
export type ExploreSource = { url: string; name: string; group?: string }
export type ExploreKind = { title: string; url?: string }
const getExploreSources = () =>
  ajax.get<LeagdoApiResponse<ExploreSource[]>>('getExploreSources')
const getExploreKinds = (sourceUrl: string) =>
  ajax.get<LeagdoApiResponse<ExploreKind[]>>('getExploreKinds', {
    params: { sourceUrl },
  })
const exploreBooks = (sourceUrl: string, url: string, page: number) =>
  ajax.get<LeagdoApiResponse<Book[]>>('exploreBooks', {
    params: { sourceUrl, url, page },
  })
const getBookSourceGroups = () =>
  ajax.get<LeagdoApiResponse<string[]>>('getBookSourceGroups')

const getChapterList = (/** @type {string} */ bookUrl: string) =>
  ajax.get<LeagdoApiResponse<BookChapter[]>>(
    'getChapterList?url=' + encodeURIComponent(bookUrl),
  )

const getBookContent = (
  /** @type {string} */ bookUrl: string,
  /** @type {number} */ chapterIndex: number,
) =>
  ajax.get<LeagdoApiResponse<string>>(
    'getBookContent?url=' +
      encodeURIComponent(bookUrl) +
      '&index=' +
      chapterIndex,
  )

// webSocket
const search = (
  searchKey: string,
  onReceive: (data: SeachBook[]) => void,
  onFinish: () => void,
  group = '',
) => {
  const socket = new WebSocket(
    new URL('searchBook', legado_webSocket_entry_point),
  )
  socket.onerror = event => {
    onFinish()
    wsOnError?.call(socket, event)
  }

  socket.onopen = () => {
    socket.send(JSON.stringify({ key: searchKey, group }))
  }
  socket.onmessage = event => {
    try {
      onReceive(JSON.parse(event.data))
      wsOnMessage?.call(socket, event)
    } catch {
      onFinish()
    }
  }

  socket.onclose = () => {
    onFinish()
  }
  return socket
}

const saveBook = (book: BaseBook) =>
  ajax.post<LeagdoApiResponse<string>>('saveBook', book)
const deleteBook = (book: BaseBook) =>
  ajax.post<LeagdoApiResponse<string>>('deleteBook', book)

const isBookSource = () => sourceMode.value === 'book'

// 源编辑API
// Http
const getSources = () =>
  isBookSource() ? ajax.get('getBookSources') : ajax.get('getRssSources')

const saveSource = (data: Source) =>
  isBookSource()
    ? ajax.post<LeagdoApiResponse<string>>('saveBookSource', data)
    : ajax.post<LeagdoApiResponse<string>>('saveRssSource', data)

const saveSources = (data: Source[]) =>
  isBookSource()
    ? ajax.post<LeagdoApiResponse<Source[]>>('saveBookSources', data)
    : ajax.post<LeagdoApiResponse<Source[]>>('saveRssSources', data)

const deleteSource = (data: Source[]) =>
  isBookSource()
    ? ajax.post<LeagdoApiResponse<string>>('deleteBookSources', data)
    : ajax.post<LeagdoApiResponse<string>>('deleteRssSources', data)

// webSocket
const debug = (
  /** @type {string} */ sourceUrl: string,
  /** @type {string} */ searchKey: string,
  /** @type {(data: string) => void} */ onReceive: (data: string) => void,
  /** @type {() => void} */ onFinish: () => void,
) => {
  const url = new URL(
    `${isBookSource() ? 'bookSource' : 'rssSource'}Debug`,
    legado_webSocket_entry_point,
  )

  const socket = new WebSocket(url)
  socket.onerror = event => {
    onFinish()
    wsOnError?.call(socket, event)
  }
  socket.onopen = () => {
    socket.send(JSON.stringify({ tag: sourceUrl, key: searchKey }))
  }
  socket.onmessage = event => {
    onReceive(event.data)
    wsOnMessage?.call(socket, event)
  }

  socket.onclose = () => {
    onFinish()
  }
  return socket
}

/**
 * 书籍封面统一通过阅读 App 获取。
 * @param {string} coverUrl
 */
const getProxyCoverUrl = (coverUrl: string) => {
  const proxyEndpoint = new URL('cover', legado_http_entry_point).toString()
  const url = coverUrl.startsWith(proxyEndpoint + '?')
    ? new URL(coverUrl)
    : new URL(
        'cover?path=' + encodeURIComponent(coverUrl),
        legado_http_entry_point,
      )
  // 固定版本绕过旧首帧缓存，后续仍使用浏览器缓存。
  url.searchParams.set('v', 'original2')
  return url.toString()
}
/**
 * 正文图片统一通过阅读 App 获取，沿用书源请求头、缓存及解密逻辑。
 * @param {string} bookUrl
 * @param {string} src
 * @param {number|`${number}`} width
 */
const getProxyImageUrl = (
  bookUrl: string,
  src: string,
  width: number | `${number}`,
) => {
  const proxyEndpoint = new URL('image', legado_http_entry_point).toString()
  if (src.startsWith(proxyEndpoint + '?')) return src
  return new URL(
    'image?path=' +
      encodeURIComponent(src) +
      '&url=' +
      encodeURIComponent(bookUrl) +
      '&width=' +
      width,
    legado_http_entry_point,
  ).toString()
}

export default {
  getExploreSources,
  getExploreKinds,
  exploreBooks,
  getReadConfig,
  saveReadConfig,
  saveBookProgress,
  saveBookProgressWithBeacon,
  getBookShelf,
  getBookSourceGroups,
  getChapterList,
  getBookContent,
  search,
  saveBook,
  deleteBook,

  getSources,
  saveSources,
  saveSource,
  deleteSource,
  debug,

  getProxyCoverUrl,
  getProxyImageUrl,
}

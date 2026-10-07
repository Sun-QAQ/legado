<template>
  <div
    class="chapter-wrapper"
    @wheel="onMangaWheel"
    :style="bodyTheme"
    :class="{
      night: isNight,
      'manga-mode': mangaMode,
      'focus-mode': focusMode,
    }"
  >
    <header class="reader-toolbar">
      <el-button @click="toShelf">← 返回书架</el-button
      ><span class="reader-book"
        >{{ store.readingBook.name
        }}<small>{{ store.readingBook.author }}</small></span
      >
      <div class="inline-actions">
        <el-button @click="popCataVisible = true">目录</el-button
        ><el-button :disabled="noPoint" @click="readSettingsVisible = true"
          >阅读设置</el-button
        ><el-button :aria-pressed="focusMode" @click="toggleFocusMode">{{
          focusMode ? '退出专注' : '专注阅读'
        }}</el-button>
      </div>
    </header>
    <div v-if="!focusMode" class="reader-navigation">
      <el-button
        :disabled="noPoint || isLoading || chapterIndex === 0"
        @click="toPreChapter"
        >← 上一章</el-button
      ><span class="muted"
        >{{ catalog.length ? chapterIndex + 1 : 0 }} /
        {{ catalog.length }} 章</span
      ><el-button
        :disabled="noPoint || isLoading || chapterIndex >= catalog.length - 1"
        @click="toNextChapter"
        >下一章 →</el-button
      >
    </div>
    <el-drawer
      v-model="popCataVisible"
      title="章节目录"
      :size="miniInterface ? '92%' : '520px'"
      direction="ltr"
      ><PopCatalog @getContent="getContent"
    /></el-drawer>
    <el-drawer
      v-model="readSettingsVisible"
      title="阅读设置"
      :size="miniInterface ? '94%' : '480px'"
      ><read-settings
    /></el-drawer>
    <article
      class="chapter"
      :class="{ 'manga-chapter': mangaMode }"
      ref="content"
      :style="chapterTheme"
    >
      <div class="content">
        <div class="top-bar" ref="top"></div>
        <manga-reader
          v-if="mangaMode && currentMangaChapter && showContent"
          ref="mangaRef"
          :key="currentMangaChapter.index"
          :pages="mangaPages"
          :position="chapterPos"
          :title="currentMangaChapter.title"
          :book-url="store.readingBook.bookUrl"
          :image-width="store.config.readWidth"
          :busy="isLoading || noPoint"
          :has-previous-chapter="chapterIndex > 0"
          :has-next-chapter="chapterIndex < catalog.length - 1"
          @progress="onReadedLengthChange(chapterIndex, $event)"
          @previous-chapter="toPreviousMangaChapter"
          @next-chapter="toNextChapter"
        />
        <template v-if="!mangaMode">
          <div
            v-for="data in chapterData"
            :key="data.index"
            :chapterIndex="data.index"
            ref="chapter"
          >
            <chapter-content
              ref="chapterRef"
              :chapterIndex="data.index"
              :contents="data.content"
              :title="data.title"
              :spacing="store.config.spacing"
              :fontSize="fontSize"
              :fontFamily="fontFamily"
              @readedLengthChange="onReadedLengthChange"
              v-if="showContent"
            />
          </div>
        </template>
        <div class="loading" ref="loading"></div>
        <div class="bottom-bar" ref="bottom"></div>
      </div>
    </article>
    <div v-if="!mangaMode" class="reader-navigation bottom-navigation">
      <el-button
        :disabled="noPoint || isLoading || chapterIndex === 0"
        @click="toPreChapter"
        >← 上一章</el-button
      >
      <div class="inline-actions">
        <el-button @click="toTop">顶部</el-button
        ><el-button @click="toBottom">底部</el-button>
      </div>
      <el-button
        :disabled="noPoint || isLoading || chapterIndex >= catalog.length - 1"
        @click="toNextChapter"
        >下一章 →</el-button
      >
    </div>
  </div>
</template>
<script setup lang="ts">
import jump from '@/plugins/jump'
import settings from '@/config/themeConfig'
import API from '@api'
import { useLoading } from '@/hooks/loading'
import { useThrottleFn } from '@vueuse/shared'
import { isNullOrBlank } from '@/utils/utils'
import { getMangaPages } from '@/utils/mangaPages'
import { createMangaWheelTurner } from '@/utils/mangaWheel'
import MangaReader from '@/components/MangaReader.vue'

const content = ref()
// loading spinner
const { isLoading, loadingWrapper } = useLoading(content, '正在获取信息')
const store = useBookStore()

const {
  catalog,
  popCataVisible,
  readSettingsVisible,
  miniInterface,
  showContent,
  bookProgress,
  theme,
  isNight,
} = storeToRefs(store)

const chapterPos = computed({
  get: () => store.readingBook.chapterPos,
  set: value => (store.readingBook.chapterPos = value),
})
const chapterIndex = computed({
  get: () => store.readingBook.chapterIndex,
  set: value => (store.readingBook.chapterIndex = value),
})
const isSeachBook = computed({
  get: () => store.readingBook.isSeachBook,
  set: value => (store.readingBook.isSeachBook = value),
})

// 当前阅读书籍readingBook持久化
watch(
  () => store.readingBook,
  book => {
    // 保存localStorage
    // localStorage.setItem(book.bookUrl, JSON.stringify(book));
    // 最近阅读
    localStorage.setItem('readingRecent', JSON.stringify(book))
    //保存 sessionStorage
    sessionStorage.setItem('chapterIndex', book.chapterIndex.toString())
    sessionStorage.setItem('chapterPos', book.chapterPos.toString())
  },
  { deep: 1 },
)

// 无限滚动
const mangaMode = computed(() => store.config.mangaMode)
const mangaRef = ref<InstanceType<typeof MangaReader>>()
const wheelTurner = createMangaWheelTurner()
const onMangaWheel = (event: WheelEvent) => {
  if (
    !mangaMode.value ||
    event.ctrlKey ||
    Math.abs(event.deltaY) <= Math.abs(event.deltaX)
  )
    return
  event.preventDefault()
  if (isLoading.value || noPoint.value || !mangaRef.value) return
  const unit =
    event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? window.innerHeight : 1
  const direction = wheelTurner(event.deltaY * unit, performance.now())
  if (direction) mangaRef.value.turnPage(direction)
}
const infiniteLoading = computed(
  () => store.config.infiniteLoading && !mangaMode.value,
)
let scrollObserver: IntersectionObserver | null
const loading = ref()
watchEffect(() => {
  if (!infiniteLoading.value) {
    scrollObserver?.disconnect()
  } else {
    if (loading.value) scrollObserver?.observe(loading.value)
  }
})
const loadMore = () => {
  const index = chapterData.value.slice(-1)[0].index
  if (catalog.value.length - 1 > index) {
    getContent(index + 1, false)
    store.saveBookProgress() // 保存的是上一章的进度，不是预载的本章进度
  }
}
// IntersectionObserver回调 底部加载
const onReachBottom = (entries: IntersectionObserverEntry[]) => {
  if (!infiniteLoading.value || isLoading.value || !chapterData.value.length)
    return
  for (const { isIntersecting } of entries) {
    if (!isIntersecting) return
    loadMore()
  }
}

// 字体
const fontFamily = computed(() => {
  if (store.config.font >= 0) {
    return settings.fonts[store.config.font]
  }
  return store.config.customFontName
})
const fontSize = computed(() => {
  return store.config.fontSize + 'px'
})

// 主题部分
const bodyColor = computed(() => settings.themes[theme.value].body)
const chapterColor = computed(() => settings.themes[theme.value].content)

const readWidth = computed(
  () =>
    Math.min(
      store.config.readWidth,
      window.innerWidth - (miniInterface.value ? 0 : 48),
    ) + 'px',
)

const bodyTheme = computed(() => {
  return {
    background: bodyColor.value,
  }
})
const chapterTheme = computed(() => {
  return {
    background: chapterColor.value,
    width: readWidth.value,
  }
})
const focusMode = ref(false)
const toggleFocusMode = () => {
  focusMode.value = !focusMode.value
  if (focusMode.value) ElMessage.info('按esc退出专注模式')
}
const onFocusEscape = (event: KeyboardEvent) => {
  if (event.key === 'Escape') focusMode.value = false
}
/**
 * pc移动端判断 最大阅读宽度修正
 * 阅读宽度最小为640px 加上工具栏 68px 52px 取较大值 为 776px
 */
const onResize = () => {
  store.setMiniInterface(window.innerWidth < 776)
  const width = store.config.readWidth /**包含padding */
  checkPageWidth(width)
}
/** 判断阅读宽度是否超出页面或者低于默认值640 */
const checkPageWidth = (readWidth: number) => {
  if (store.miniInterface) return
  if (readWidth < 640) store.config.readWidth = 640
  if (readWidth + 2 * 68 > window.innerWidth) store.config.readWidth -= 160
}
watch(
  () => store.config.readWidth,
  width => checkPageWidth(width),
)
// 顶部底部跳转
const top = ref()
const bottom = ref()
const toTop = () => {
  jump(top.value)
}
const toBottom = () => {
  jump(bottom.value)
}

// 书架路由切换
const router = useRouter()
const toShelf = () => {
  router.push('/')
}

// 获取章节内容
const chapterData = ref<{ index: number; content: string[]; title: string }[]>(
  [],
)
const currentMangaChapter = computed(() =>
  chapterData.value.find(data => data.index === chapterIndex.value),
)
const mangaPages = computed(() =>
  getMangaPages(currentMangaChapter.value?.content || []),
)
watch(mangaMode, () => {
  const position = chapterPos.value
  chapterData.value = chapterData.value.filter(
    data => data.index === chapterIndex.value,
  )
  if (!mangaMode.value) toChapterPos(position)
})
const noPoint = ref(true)
const getContent = (
  index: number,
  reloadChapter = true,
  chapterPos = 0,
  lastMangaPage = false,
) => {
  if (reloadChapter) {
    //展示进度条
    store.setShowContent(false)
    //强制滚回顶层
    jump(top.value, { duration: 0 })
    //从目录，按钮切换章节时保存进度 预加载时不保存
    saveReadingBookProgressToBrowser(index, chapterPos)
    chapterData.value = []
  }
  const bookUrl = store.readingBook.bookUrl
  if (!catalog.value[index]) return
  const { title, index: chapterIndex } = catalog.value[index]

  loadingWrapper(
    API.getBookContent(bookUrl, chapterIndex).then(
      res => {
        if (res.data.isSuccess) {
          const data = res.data.data
          const content = data.split(/\n+/)
          chapterData.value.push({ index, content, title })
          if (reloadChapter && mangaMode.value && lastMangaPage) {
            const pages = getMangaPages(content)
            saveReadingBookProgressToBrowser(
              index,
              pages.at(-1)?.chapterPos || 0,
            )
          } else if (reloadChapter && !mangaMode.value) toChapterPos(chapterPos)
        } else {
          ElMessage({ message: res.data.errorMsg, type: 'error' })
          const content = [res.data.errorMsg]
          chapterData.value.push({ index, content, title })
        }
        store.setContentLoading(true)
        noPoint.value = false
        store.setShowContent(true)
        if (!res.data.isSuccess) {
          throw res.data
        }
      },
      err => {
        const content = ['获取章节内容失败！']
        chapterData.value.push({ index, content, title })
        store.setShowContent(true)
        throw err
      },
    ),
  )
}

// 章节进度跳转和计算
const chapter = ref()
const chapterRef = ref()
const toChapterPos = (pos: number) => {
  nextTick(() => {
    if (chapterRef.value?.length === 1)
      chapterRef.value[0].scrollToReadedLength(pos)
  })
}

// 60秒保存一次进度
const saveBookProgressThrottle = useThrottleFn(
  () => store.saveBookProgress(),
  60000,
)

const onReadedLengthChange = (index: number, pos: number) => {
  saveReadingBookProgressToBrowser(index, pos)
  saveBookProgressThrottle()
}

// 文档标题
watchEffect(() => {
  document.title = catalog.value[chapterIndex.value]?.title || document.title
})

// 阅读记录保存浏览器
const saveReadingBookProgressToBrowser = (index: number, pos: number) => {
  // 保存pinia
  chapterIndex.value = index
  chapterPos.value = pos
}

// 进度同步
// 返回导航变化 同步请求会在获取书架前完成

/**
 * VisibilityChange https://developer.mozilla.org/zh-CN/docs/Web/API/Document/visibilitychange_event
 * 监听关闭页面 切换tab 返回桌面 等操作
 * 注意不用监听点击链接导航变化 不对Safari<14.5兼容处理
 **/
const onVisibilityChange = () => {
  const _bookProgress = bookProgress.value
  if (document.visibilityState == 'hidden' && _bookProgress) {
    store.saveBookProgress()
  }
}
// 定时同步

// 章节切换
const toNextChapter = () => {
  store.setContentLoading(true)
  const index = chapterIndex.value + 1
  if (typeof catalog.value[index] !== 'undefined') {
    ElMessage({
      message: '下一章',
      type: 'info',
    })
    getContent(index)
    store.saveBookProgress()
  } else {
    ElMessage({
      message: '本章是最后一章',
      type: 'error',
    })
  }
}
const toPreChapter = () => {
  store.setContentLoading(true)
  const index = chapterIndex.value - 1
  if (typeof catalog.value[index] !== 'undefined') {
    ElMessage({
      message: '上一章',
      type: 'info',
    })
    getContent(index)
    store.saveBookProgress()
  } else {
    ElMessage({
      message: '本章是第一章',
      type: 'error',
    })
  }
}
const toPreviousMangaChapter = () => {
  if (chapterIndex.value > 0 && !isLoading.value) {
    getContent(chapterIndex.value - 1, true, 0, true)
    store.saveBookProgress()
  }
}

let canJump = true
// 监听方向键
const handleKeyPress = (event: KeyboardEvent) => {
  if (
    !canJump ||
    isLoading.value ||
    noPoint.value ||
    popCataVisible.value ||
    readSettingsVisible.value ||
    /INPUT|TEXTAREA|SELECT/.test((event.target as HTMLElement)?.tagName || '')
  )
    return
  switch (event.key) {
    case 'ArrowLeft':
      event.stopPropagation()
      event.preventDefault()
      if (mangaMode.value) mangaRef.value?.turnPage(-1)
      else toPreChapter()
      break
    case 'ArrowRight':
      event.stopPropagation()
      event.preventDefault()
      if (mangaMode.value) mangaRef.value?.turnPage(1)
      else toNextChapter()
      break
    case 'ArrowUp':
      if (mangaMode.value) return
      event.stopPropagation()
      event.preventDefault()
      if (document.documentElement.scrollTop === 0) {
        ElMessage.warning('已到达页面顶部')
      } else {
        canJump = false
        jump(0 - document.documentElement.clientHeight + 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
    case 'ArrowDown':
      if (mangaMode.value) return
      event.stopPropagation()
      event.preventDefault()
      if (
        document.documentElement.clientHeight +
          document.documentElement.scrollTop ===
        document.documentElement.scrollHeight
      ) {
        ElMessage.warning('已到达页面底部')
      } else {
        canJump = false
        jump(document.documentElement.clientHeight - 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
  }
}

// 阻止默认滚动事件
const ignoreKeyPress = (event: KeyboardEvent) => {
  if (
    !/INPUT|TEXTAREA|SELECT/.test(
      (event.target as HTMLElement)?.tagName || '',
    ) &&
    !readSettingsVisible.value &&
    !popCataVisible.value &&
    (event.key === 'ArrowUp' || event.key === 'ArrowDown')
  ) {
    event.preventDefault()
    event.stopPropagation()
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onFocusEscape)
  await store.loadWebConfig()
  //获取书籍数据
  const bookUrl = sessionStorage.getItem('bookUrl')
  const name = sessionStorage.getItem('bookName')
  const author = sessionStorage.getItem('bookAuthor')
  const chapterIndex = Number(sessionStorage.getItem('chapterIndex') || 0)
  const chapterPos = Number(sessionStorage.getItem('chapterPos') || 0)
  const isSeachBook = sessionStorage.getItem('isSeachBook') === 'true'
  if (isNullOrBlank(bookUrl) || isNullOrBlank(name) || author === null) {
    ElMessage.warning('书籍信息为空，即将自动返回书架页面...')
    return setTimeout(toShelf, 500)
  }
  const book: typeof store.readingBook = {
    // @ts-expect-error: bookUrl name author is NON_Blank string here
    bookUrl,
    // @ts-expect-error: bookUrl name author is NON_Blank string here
    name,
    author,
    chapterIndex,
    chapterPos,
    isSeachBook,
  }
  onResize()
  window.addEventListener('resize', onResize)
  loadingWrapper(
    store.loadWebCatalog(book).then(chapters => {
      store.setReadingBook(book)
      if (!chapters.length) {
        ElMessage.warning('暂无章节目录')
        return
      }
      const validIndex = Math.min(
        Math.max(0, chapterIndex),
        chapters.length - 1,
      )
      getContent(validIndex, true, chapterPos)
      window.addEventListener('keyup', handleKeyPress)
      window.addEventListener('keydown', ignoreKeyPress)
      // 兼容Safari < 14
      document.addEventListener('visibilitychange', onVisibilityChange)
      //监听底部加载
      scrollObserver = new IntersectionObserver(onReachBottom, {
        rootMargin: '-100% 0% 20% 0%',
      })
      if (infiniteLoading.value === true) scrollObserver.observe(loading.value)
      //第二次点击同一本书 页面标题不会变化
      document.title = '...'
      document.title =
        (name as string) +
          ' | ' +
          chapters[Math.min(Math.max(0, chapterIndex), chapters.length - 1)]
            ?.title || ''
    }),
  )
})

onUnmounted(() => {
  window.removeEventListener('keydown', onFocusEscape)
  window.removeEventListener('keyup', handleKeyPress)
  window.removeEventListener('keydown', ignoreKeyPress)
  window.removeEventListener('resize', onResize)
  // 兼容Safari < 14
  document.removeEventListener('visibilitychange', onVisibilityChange)
  store.saveBookProgress()
  readSettingsVisible.value = false
  popCataVisible.value = false
  scrollObserver?.disconnect()
  scrollObserver = null
})

const addToBookShelfConfirm = async () => {
  const book = store.readingBook
  // 阅读的是搜索的书籍 并未在书架
  if (book.isSeachBook === true) {
    await ElMessageBox.confirm(`是否将《${book.name}》放入书架？`, '放入书架', {
      confirmButtonText: '确认',
      cancelButtonText: '否',
      type: 'info',
      /*
        ElMessageBox.confirm默认在触发hashChange事件时自动关闭
        按下物理返回键时触发hashChange事件
        使用router.push("/")则不会触发hashChange事件
        */
      closeOnHashChange: false,
    })
      .then(() => {
        //选择是，无动作
        isSeachBook.value = false
      })
      .catch(async () => {
        //选择否，删除书籍
        await API.deleteBook(book)
      })
      .finally(() => sessionStorage.removeItem('isSeachBook'))
  }
}
onBeforeRouteLeave(async (to, from, next) => {
  console.log('onBeforeRouteLeave')
  // 弹窗时停止响应按键翻页
  window.removeEventListener('keyup', handleKeyPress)
  await addToBookShelfConfirm()
  next()
})
</script>

<style scoped>
.chapter-wrapper {
  min-height: 100vh;
  padding: 0 24px 32px;
  color: #292730;
}
.chapter-wrapper.night {
  color: #ddd7ca;
}
.reader-toolbar {
  position: sticky;
  top: 0;
  z-index: 5;
  margin: 0 -24px;
  padding: 16px 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  background: var(--web-surface);
  color: var(--web-text);
  border-bottom: 1px solid var(--web-line);
}
.reader-book {
  font-size: 14px;
  overflow-wrap: anywhere;
}
.reader-book small {
  font-size: 12px;
  display: block;
  color: var(--web-muted);
}
.reader-navigation {
  max-width: 1000px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 22px auto;
  font-size: 12px;
}
.chapter {
  margin: 24px auto;
  border: 1px solid var(--web-line);
  border-radius: 12px;
  padding: 0 48px;
  min-height: 60vh;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.chapter .content {
  font-size: 18px;
  line-height: 1.8;
}
.top-bar,
.bottom-bar {
  height: 38px;
}
.chapter :deep(.title) {
  font:
    26px/1.6 SimSun,
    serif;
  text-align: center;
  letter-spacing: 1px;
  margin: 0 0 32px;
}
.chapter:not(.manga-chapter) :deep(img) {
  max-width: 100%;
  height: auto;
}
.bottom-navigation {
  padding-bottom: 20px;
}
.chapter.manga-chapter {
  padding: 0;
  margin: 12px auto;
  min-height: 0;
}
.manga-chapter .top-bar,
.manga-chapter .bottom-bar {
  height: 0;
}
.manga-mode .reader-navigation {
  margin: 12px auto;
  width: 100%;
  flex-shrink: 0;
}
.chapter-wrapper.manga-mode {
  box-sizing: border-box;
  height: 100dvh;
  min-height: 0;
  padding-bottom: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  overscroll-behavior: none;
}
.manga-mode .reader-toolbar {
  flex-shrink: 0;
}
.manga-mode .manga-chapter {
  box-sizing: border-box;
  flex: 1;
  min-height: 0;
  margin: 0 auto;
  overflow: hidden;
}
.manga-chapter .content,
.manga-chapter :deep(.manga-reader) {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.manga-chapter :deep(.manga-stage) {
  flex: 1;
  height: auto;
  min-height: 0;
  overflow: hidden;
}
.manga-chapter :deep(.manga-caption) {
  flex-shrink: 0;
}
.chapter-wrapper.manga-mode.focus-mode {
  position: fixed;
  inset: 0;
  z-index: 1000;
  height: 100dvh;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}
.manga-mode.focus-mode .reader-toolbar,
.manga-mode.focus-mode :deep(.manga-caption) {
  display: none;
}
.manga-mode.focus-mode .chapter {
  width: 100% !important;
  height: 100%;
  margin: 0;
  border: 0;
  border-radius: 0;
}
.manga-mode.focus-mode :deep(.manga-stage) {
  height: auto;
}
.reader-toolbar .inline-actions {
  gap: 8px;
}
@media (max-width: 600px) {
  .chapter-wrapper {
    padding: 0 0 24px;
  }
  .reader-toolbar {
    margin: 0;
    padding: 12px 16px;
    gap: 12px;
  }
  .reader-toolbar .inline-actions {
    width: 100%;
  }
  .reader-navigation {
    padding: 0 16px;
    margin: 16px auto;
  }
  .chapter {
    border-radius: 0;
    border-left: 0;
    border-right: 0;
    margin: 0 auto;
    padding: 0 22px;
  }
  .chapter :deep(.title) {
    font-size: 22px;
  }
  .bottom-navigation .inline-actions {
    gap: 4px;
  }
}
</style>

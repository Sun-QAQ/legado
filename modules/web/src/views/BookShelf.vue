<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">LEGADO READING SPACE</div>
      <h1>{{ online ? '在线搜索' : '我的书架' }}</h1>
      <p>
        {{
          online
            ? '从 App 已启用的书源中，找到下一段好故事。'
            : '把时间留给喜欢的故事。'
        }}
      </p>
    </div>
    <div v-if="!online" class="inline-actions">
      <el-button :icon="Refresh" :loading="loading" @click="loadShelf"
        >刷新书架</el-button
      ><el-button type="primary" :icon="Search" @click="$router.push('/search')"
        >找一本书</el-button
      >
    </div>
  </div>
  <template v-if="online"
    ><form class="search-box web-panel" @submit.prevent="searchBook">
      <el-input
        v-model="searchWord"
        placeholder="输入书名或作者"
        aria-label="在线搜索关键词"
        :prefix-icon="Search"
        clearable
      /><el-select
        v-model="searchGroup"
        class="search-group"
        aria-label="搜索书源分组"
        placeholder="全部书源"
        filterable
        :loading="groupsLoading"
        :disabled="groupsLoading"
      >
        <el-option label="全部书源" value="" />
        <el-option
          v-for="group in sourceGroups"
          :key="group"
          :label="group"
          :value="group"
        /> </el-select
      ><el-button type="primary" native-type="submit" :loading="searching"
        >搜索书籍</el-button
      ><el-button v-if="searching" @click="stopSearch">停止搜索</el-button>
    </form>
    <p class="search-hint muted">
      选择结果可查看书籍详情，再加入书架或开始阅读。{{
        searching
          ? '正在接收书源结果…'
          : searched
            ? `找到 ${books.length} 本书`
            : '输入关键词开始搜索。'
      }}
    </p></template
  >
  <template v-else
    ><section v-if="recent" class="continue-card web-panel">
      <div class="recent-cover"><book-cover :book="recent" /></div>
      <div class="continue-info">
        <div class="eyebrow">CONTINUE READING / 继续上次阅读</div>
        <h2 class="recent-title" :title="recent.name">{{ recent.name }}</h2>
        <p class="muted">
          {{ recent.author }} · {{ recent.durChapterTitle || '开始阅读' }}
        </p>
        <el-progress
          :percentage="bookProgress(recent)"
          :stroke-width="4"
          :show-text="false"
        /><small class="muted">已读 {{ bookProgress(recent) }}%</small>
      </div>
      <el-button type="primary" @click="startBookReading(router, recent)">
        继续阅读 →
      </el-button>
    </section>
    <div class="shelf-toolbar">
      <div class="shelf-tabs" aria-label="书架筛选">
        <button
          v-for="label in filters"
          :key="label"
          :class="{ active: filter === label }"
          :aria-pressed="filter === label"
          @click="filter = label"
        >
          {{ label }}
        </button>
      </div>
      <div class="shelf-tools">
        <el-input
          v-model="searchWord"
          placeholder="搜索书名或作者"
          aria-label="搜索书架"
          clearable
        /><el-select v-model="sort" aria-label="书架排序"
          ><el-option label="最近阅读" value="recent" /><el-option
            label="书名排序"
            value="name" /><el-option
            label="阅读进度"
            value="progress" /></el-select
        ><el-select v-model="pageSize" aria-label="每页书籍数量"
          ><el-option
            v-for="size in [50, 100, 200]"
            :key="size"
            :label="`${size} 本/页`"
            :value="size" /></el-select
        ><el-button
          :icon="list ? Grid : List"
          aria-label="切换网格或列表"
          :aria-pressed="list"
          @click="list = !list"
        />
      </div></div
  ></template>
  <section v-loading="loading" class="shelf-content" aria-label="书籍列表">
    <div v-if="error" class="web-empty">
      <h2>暂时无法加载书架</h2>
      <p>{{ error }}</p>
      <el-button @click="loadShelf">重试</el-button
      ><el-button @click="$router.push('/settings')">检查连接</el-button>
    </div>
    <book-items
      v-else-if="books.length"
      :books="visibleBooks"
      :is-search="online"
      :list="list && !online"
      @book-click="openBook"
    />
    <div v-else-if="!loading" class="web-empty">
      <h2>
        {{
          online
            ? searching
              ? '正在查找书籍…'
              : searched
                ? '没有找到相关书籍'
                : '下一本想读什么？'
            : store.shelf.length
              ? '没有符合条件的书籍'
              : '书架还是空的'
        }}
      </h2>
      <p>
        {{
          online
            ? '试试书名或作者，也可以检查 App 的书源是否启用。'
            : '换个筛选条件，或通过在线搜索找一本书。'
        }}
      </p>
      <el-button v-if="!online" @click="$router.push('/search')"
        >在线搜索</el-button
      >
    </div>
  </section>
  <div v-if="!online && books.length && !error" class="shelf-pagination">
    <span class="muted"
      >共 {{ books.length }} 本 · 第 {{ currentPage }} /
      {{ pageCount }} 页</span
    >
    <el-pagination
      v-model:current-page="currentPage"
      :page-size="pageSize"
      :total="books.length"
      :pager-count="5"
      layout="prev, pager, next"
      aria-label="书架分页"
    />
  </div>
</template>
<script setup lang="ts">
import { Grid, List, Refresh, Search } from '@element-plus/icons-vue'
import API from '@api'
import type { Book, SeachBook } from '@/book'
import { bookProgress } from '@/utils/bookPresentation'
import { openBookDetail, startBookReading } from '@/utils/bookNavigation'
const store = useBookStore(),
  route = useRoute(),
  router = useRouter()
const online = computed(() => route.name === 'search')
const filters = ['全部书籍', '阅读中', '已读完']
const filter = ref('全部书籍'),
  sort = ref('recent'),
  searchWord = ref(
    online.value ? sessionStorage.getItem('web-book-search-word') || '' : '',
  )
const list = ref(localStorage.getItem('web-shelf-layout') === 'list')
const pageSize = ref(50),
  currentPage = ref(1)
watch(
  [filter, sort, searchWord, pageSize],
  () => {
    currentPage.value = 1
  },
  { flush: 'sync' },
)
watch(list, value =>
  localStorage.setItem('web-shelf-layout', value ? 'list' : 'grid'),
)
const loading = ref(false),
  searching = ref(false),
  searched = ref(store.searchBooks.length > 0),
  error = ref('')
const searchGroup = ref(sessionStorage.getItem('web-book-search-group') || ''),
  sourceGroups = ref<string[]>([]),
  groupsLoading = ref(false)
let socket: WebSocket | undefined
let disposed = false,
  searchGeneration = 0
const recent = computed(() => {
  let url = ''
  try {
    url =
      JSON.parse(localStorage.getItem('readingRecent') || '{}').bookUrl || ''
  } catch {
    /* 忽略无效旧记录。 */
  }
  return (
    store.shelf.find(b => b.bookUrl === url) ||
    store.shelf.find(b => b.durChapterTime > 0)
  )
})
const books = computed(() => {
  if (online.value) return store.searchBooks
  let items = store.shelf.filter(b =>
    `${b.name}${b.author}`.includes(searchWord.value.trim()),
  )
  if (filter.value === '阅读中')
    items = items.filter(b => b.durChapterTime > 0 && bookProgress(b) < 100)
  if (filter.value === '已读完')
    items = items.filter(b => bookProgress(b) === 100)
  return [...items].sort((a, b) =>
    sort.value === 'name'
      ? a.name.localeCompare(b.name, 'zh-CN')
      : sort.value === 'progress'
        ? bookProgress(b) - bookProgress(a)
        : (b.durChapterTime || 0) - (a.durChapterTime || 0),
  )
})
const pageCount = computed(() =>
  Math.max(1, Math.ceil(books.value.length / pageSize.value)),
)
const visibleBooks = computed(() => {
  if (online.value) return books.value
  const start = (currentPage.value - 1) * pageSize.value
  return books.value.slice(start, start + pageSize.value)
})
watch(
  pageCount,
  count => {
    currentPage.value = Math.min(currentPage.value, count)
  },
  { flush: 'sync' },
)
const loadShelf = async () => {
  loading.value = true
  error.value = ''
  try {
    await store.loadWebConfig()
    await store.saveBookProgress()
    await store.loadBookShelf()
  } catch {
    error.value = '请确认阅读 App 的 Web 服务已开启，且服务地址可用。'
  } finally {
    loading.value = false
  }
}
const stopSearch = () => {
  searchGeneration++
  socket?.close()
  socket = undefined
  searching.value = false
}
const loadSearchGroups = async () => {
  groupsLoading.value = true
  try {
    await store.loadWebConfig()
    const { data } = await API.getBookSourceGroups()
    if (disposed || !online.value) return
    if (!data.isSuccess) throw new Error(data.errorMsg)
    sourceGroups.value = data.data
    if (!sourceGroups.value.includes(searchGroup.value)) searchGroup.value = ''
  } catch {
    if (!disposed && online.value)
      ElMessage.warning(
        '书源分组加载失败，可继续搜索全部书源，重新进入此页可重试',
      )
  } finally {
    groupsLoading.value = false
  }
}
const searchBook = () => {
  const key = searchWord.value.trim()
  if (!key) return ElMessage.info('请输入书名或作者')
  sessionStorage.setItem('web-book-search-word', key)
  sessionStorage.setItem('web-book-search-group', searchGroup.value)
  stopSearch()
  store.clearSearchBooks()
  searched.value = true
  searching.value = true
  const generation = searchGeneration
  try {
    socket = API.search(
      key,
      results => {
        if (disposed || generation !== searchGeneration) return
        const existing = new Set(
          store.searchBooks.map(b => `${b.origin}:${b.bookUrl}`),
        )
        store.searchBooks.push(
          ...results.filter(b => {
            const id = `${b.origin}:${b.bookUrl}`
            if (existing.has(id)) return false
            existing.add(id)
            return true
          }),
        )
      },
      () => {
        if (!disposed && generation === searchGeneration)
          searching.value = false
      },
      searchGroup.value,
    )
  } catch {
    searching.value = false
    ElMessage.error('无法开始搜索，请检查连接')
  }
}
const openBook = (book: Book | SeachBook) =>
  openBookDetail(router, book, online.value ? 'search' : 'shelf')
const searchFromNavigation = async () => {
  const key = typeof route.query.q === 'string' ? route.query.q.trim() : ''
  if (!online.value || !key) return
  searchWord.value = key
  try {
    await store.loadWebConfig()
    if (!disposed && online.value && route.query.q === key) searchBook()
  } catch {
    error.value = '请确认阅读 App 的 Web 服务已开启，且服务地址可用。'
  }
}
watch(() => [online.value, route.query.q], searchFromNavigation)
watch(online, active => {
  if (active) void loadSearchGroups()
})
onMounted(() => {
  if (!online.value) loadShelf()
  else {
    void loadSearchGroups()
    if (route.query.q) void searchFromNavigation()
  }
})
onUnmounted(() => {
  disposed = true
  stopSearch()
})
</script>
<style scoped>
.continue-card {
  display: grid;
  grid-template-columns: 115px minmax(0, 1fr) auto;
  align-items: center;
  gap: 30px;
  margin-bottom: 30px;
  padding: 28px 32px;
}
.recent-cover {
  width: 104px;
  transform: rotate(-4deg);
}
.continue-info h2 {
  font:
    25px/1.5 SimSun,
    serif;
  margin: 6px 0;
}
.continue-info {
  min-width: 0;
}
.recent-title {
  min-height: 3em;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.continue-info p {
  margin: 4px 0 12px;
}
.continue-info .el-progress {
  max-width: 240px;
}
.continue-info small {
  font-size: 12px;
}
.shelf-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin: 0 0 22px;
}
.shelf-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.shelf-tabs button {
  border: 0;
  background: transparent;
  color: var(--web-muted);
  border-radius: 8px;
  padding: 9px 12px;
  cursor: pointer;
}
.shelf-tabs button.active {
  background: var(--web-tint);
  color: var(--web-primary);
}
.shelf-tools {
  display: flex;
  gap: 9px;
  align-items: center;
  flex-wrap: wrap;
}
.shelf-tools .el-input {
  width: 180px;
}
.shelf-tools .el-select {
  width: 130px;
}
.shelf-content {
  min-height: 200px;
}
.shelf-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;
  font-size: 12px;
}
.search-box {
  display: flex;
  gap: 12px;
  padding: 18px;
}
.search-box .el-input {
  flex: 1;
  min-width: 120px;
}
.search-box .search-group {
  width: 180px;
  flex-shrink: 0;
}
.search-group :deep(.el-select__input:focus-visible) {
  outline: none;
}
.search-hint {
  font-size: 12px;
  margin: 15px 0 28px;
}
.web-empty h2 {
  font-size: 20px;
}
.web-empty .el-button {
  margin-top: 12px;
}
@media (max-width: 1000px) {
  .continue-card {
    grid-template-columns: 95px minmax(0, 1fr);
    gap: 18px;
    padding: 24px;
  }
  .continue-card > .el-button {
    grid-column: 2;
    justify-self: start;
  }
  .recent-cover {
    grid-row: 1/3;
    width: 85px;
  }
}
@media (max-width: 600px) {
  .continue-card {
    grid-template-columns: 80px minmax(0, 1fr);
    padding: 20px;
    gap: 16px;
  }
  .recent-cover {
    width: 73px;
  }
  .continue-info h2 {
    font-size: 21px;
  }
  .continue-info p {
    font-size: 12px;
  }
  .shelf-tools {
    width: 100%;
  }
  .shelf-tools .el-input {
    width: 120px;
    flex: 1;
  }
  .shelf-tools .el-select {
    width: 112px;
  }
  .shelf-tabs button {
    font-size: 12px;
    padding: 8px 10px;
  }
  .search-box {
    flex-wrap: wrap;
    padding: 14px;
  }
  .search-box .el-input {
    flex-basis: 100%;
  }
  .search-box .search-group {
    flex: 1;
    min-width: 130px;
  }
}
</style>

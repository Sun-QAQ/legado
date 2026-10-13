<template>
  <section>
    <div class="page-heading">
      <div>
        <div class="eyebrow">LEGADO READING SPACE</div>
        <h1>书源发现</h1>
        <p>从书源的分类与榜单中，遇见下一本想读的书。</p>
      </div>
      <el-button :loading="sourcesLoading" @click="loadSources"
        >刷新书源</el-button
      >
    </div>
    <div class="explore-controls">
      <el-select
        v-model="sourceGroup"
        class="explore-group"
        filterable
        aria-label="发现书源分组"
        placeholder="全部分组"
        :disabled="sourcesLoading"
      >
        <el-option label="全部分组" value="" />
        <el-option
          v-for="group in sourceGroups"
          :key="group"
          :label="group"
          :value="`group:${group}`"
        />
        <el-option v-if="hasUngrouped" label="未分组" value="ungrouped" />
      </el-select>
      <el-select
        v-model="sourceUrl"
        filterable
        placeholder="选择发现书源"
        aria-label="选择发现书源"
        :loading="sourcesLoading"
        :disabled="sourcesLoading"
        @change="loadKinds"
      >
        <el-option
          v-for="source in filteredSources"
          :key="source.url"
          :value="source.url"
          :label="source.name"
        />
      </el-select>
      <span class="muted">{{ filteredSources.length }} 个书源</span>
      <router-link to="/bookSource">管理书源</router-link>
    </div>
    <div v-loading="kindsLoading" class="explore-kinds" aria-label="发现分类">
      <template v-for="(kind, index) in kinds" :key="index">
        <el-button
          v-if="kind.url"
          :type="category === kind.url ? 'primary' : 'default'"
          :aria-pressed="category === kind.url"
          @click="selectKind(kind.url)"
          >{{ kind.title }}</el-button
        >
        <span v-else class="muted">{{ kind.title }}</span>
      </template>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <div v-if="error" class="explore-retry">
      <el-button @click="retry">重试</el-button>
    </div>
    <div
      v-loading="booksLoading"
      class="explore-results"
      aria-live="polite"
      :aria-busy="booksLoading"
    >
      <div v-if="books.length" class="explore-grid">
        <article v-for="book in books" :key="book.bookUrl" class="explore-book">
          <el-image
            :src="API.getProxyCoverUrl(book.coverUrl || '')"
            fit="cover"
            loading="lazy"
          >
            <template #error
              ><div class="explore-cover-fallback">
                {{ book.name }}
              </div></template
            >
          </el-image>
          <div class="explore-book-info">
            <h2 :title="book.name">{{ book.name }}</h2>
            <p class="muted">{{ book.author || '佚名' }}</p>
            <p v-if="book.kind" class="muted explore-tags" :title="book.kind">
              {{ book.kind }}
            </p>
            <p class="explore-intro">
              {{
                book.intro?.replace(/<[^>]*>/g, '') ||
                book.latestChapterTitle ||
                '暂无简介'
              }}
            </p>
            <el-button
              :disabled="inShelf(book)"
              :loading="adding.has(book.bookUrl)"
              @click="addBook(book)"
              >{{ inShelf(book) ? '已在书架' : '加入书架' }}</el-button
            >
          </div>
        </article>
      </div>
      <el-empty
        v-else-if="!sourcesLoading && !kindsLoading && !booksLoading && !error"
        :description="emptyText"
      />
    </div>
    <div v-if="books.length" class="explore-more">
      <el-button
        :loading="booksLoading"
        :disabled="!hasMore"
        @click="loadBooks"
        >{{ hasMore ? '加载更多' : '已加载全部书籍' }}</el-button
      >
    </div>
  </section>
</template>
<script setup lang="ts">
import API, { type ExploreSource, type ExploreKind } from '@api'
import type { Book } from '@/book'

defineOptions({ name: 'BookExplore' })

const store = useBookStore()
const sources = ref<ExploreSource[]>([])
const sourceGroup = ref('')
// 与 App 的书源分组分隔符保持一致，一个书源可以属于多个分组。
const groupsOf = (source: ExploreSource) =>
  (source.group || '')
    .split(/[,;，；]/)
    .map(group => group.trim())
    .filter(Boolean)
const sourceGroups = computed(() =>
  [...new Set(sources.value.flatMap(groupsOf))].sort((a, b) =>
    a.localeCompare(b, 'zh-CN'),
  ),
)
const hasUngrouped = computed(() =>
  sources.value.some(source => !groupsOf(source).length),
)
const filteredSources = computed(() =>
  sources.value.filter(
    source =>
      !sourceGroup.value ||
      (sourceGroup.value === 'ungrouped'
        ? !groupsOf(source).length
        : groupsOf(source).includes(sourceGroup.value.slice(6))),
  ),
)
const kinds = ref<ExploreKind[]>([])
const books = ref<Book[]>([])
const sourceUrl = ref('')
const category = ref('')
const sourcesLoading = ref(false)
const kindsLoading = ref(false)
const booksLoading = ref(false)
const hasMore = ref(true)
const error = ref('')
const adding = reactive(new Set<string>())
let page = 1
let generation = 0
let disposed = false
const emptyText = computed(() =>
  !sources.value.length
    ? '暂无启用发现的书源，请在书源管理中添加或启用。'
    : !sourceUrl.value
      ? '请选择此分组中的发现书源。'
      : !kinds.value.length
        ? '此书源暂无发现分类。'
        : !category.value
          ? '请选择一个发现分类。'
          : '此分类暂无书籍。',
)
const inShelf = (book: Book) =>
  store.shelf.some(item => item.bookUrl === book.bookUrl)
const loadSources = async () => {
  const current = ++generation
  sourcesLoading.value = true
  kindsLoading.value = booksLoading.value = false
  sourceUrl.value = category.value = error.value = ''
  kinds.value = []
  books.value = []
  try {
    const { data } = await API.getExploreSources()
    if (disposed || current !== generation) return
    if (!data.isSuccess) throw new Error(data.errorMsg)
    sources.value = data.data
    if (
      sourceGroup.value === 'ungrouped'
        ? !hasUngrouped.value
        : sourceGroup.value &&
          !sourceGroups.value.includes(sourceGroup.value.slice(6))
    ) {
      sourceGroup.value = ''
    }
  } catch (e) {
    if (!disposed && current === generation)
      error.value =
        e instanceof Error && e.message
          ? e.message
          : '书源加载失败，请检查连接。'
  } finally {
    if (!disposed && current === generation) sourcesLoading.value = false
  }
}
const loadKinds = async () => {
  const current = ++generation
  kindsLoading.value = true
  booksLoading.value = false
  category.value = error.value = ''
  kinds.value = []
  books.value = []
  try {
    const { data } = await API.getExploreKinds(sourceUrl.value)
    if (disposed || current !== generation) return
    if (!data.isSuccess) throw new Error(data.errorMsg)
    kinds.value = data.data
  } catch (e) {
    if (!disposed && current === generation)
      error.value =
        e instanceof Error && e.message ? e.message : '发现分类加载失败。'
  } finally {
    if (!disposed && current === generation) kindsLoading.value = false
  }
}
watch(sourceGroup, () => {
  if (!sourceUrl.value) return
  if (filteredSources.value.some(source => source.url === sourceUrl.value))
    return
  // 切换分组后清除不属于当前分组的选择，并忽略之前尚未完成的请求。
  generation++
  sourceUrl.value = category.value = error.value = ''
  kinds.value = []
  books.value = []
  kindsLoading.value = booksLoading.value = false
  hasMore.value = true
  page = 1
})
const selectKind = (url: string) => {
  generation++
  category.value = url
  books.value = []
  booksLoading.value = false
  hasMore.value = true
  page = 1
  loadBooks()
}
const loadBooks = async () => {
  if (booksLoading.value || !category.value || !hasMore.value) return
  const current = generation
  booksLoading.value = true
  error.value = ''
  try {
    const { data } = await API.exploreBooks(
      sourceUrl.value,
      category.value,
      page,
    )
    if (disposed || current !== generation) return
    if (!data.isSuccess) throw new Error(data.errorMsg)
    const existing = new Set(books.value.map(book => book.bookUrl))
    const fresh = data.data.filter(book => {
      if (existing.has(book.bookUrl)) return false
      existing.add(book.bookUrl)
      return true
    })
    books.value.push(...fresh)
    hasMore.value = fresh.length > 0
    page++
  } catch (e) {
    if (!disposed && current === generation)
      error.value =
        e instanceof Error && e.message ? e.message : '书籍加载失败。'
  } finally {
    if (!disposed && current === generation) booksLoading.value = false
  }
}
const retry = () =>
  category.value ? loadBooks() : sourceUrl.value ? loadKinds() : loadSources()
const addBook = async (book: Book) => {
  if (adding.has(book.bookUrl) || inShelf(book)) return
  adding.add(book.bookUrl)
  try {
    const { data } = await API.saveBook(book)
    if (!data.isSuccess) throw new Error(data.errorMsg)
    await store.loadBookShelf()
    ElMessage.success('已加入书架')
  } catch (e) {
    ElMessage.error(
      e instanceof Error && e.message ? e.message : '加入书架失败',
    )
  } finally {
    adding.delete(book.bookUrl)
  }
}
onMounted(() => {
  loadSources()
})
onActivated(() => {
  document.title = '书源发现 · 阅读'
  // 页面缓存保留发现进度，书架状态仍同步其它页面的添加或删除操作。
  store.loadBookShelf().catch(() => {})
})
onBeforeUnmount(() => {
  disposed = true
  generation++
})
</script>
<style scoped>
.explore-controls,
.explore-kinds {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 24px;
}
.explore-controls .el-select {
  width: min(360px, 100%);
}
.explore-controls .explore-group {
  width: min(220px, 100%);
}
.explore-controls :deep(.el-select__input:focus-visible) {
  outline: none;
}
.explore-controls a {
  color: var(--web-primary);
}
.explore-kinds {
  min-height: 32px;
}
.explore-kinds .el-button {
  margin: 0;
}
.explore-results {
  min-height: 180px;
}
.explore-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(320px, 100%), 1fr));
  gap: 20px;
}
.explore-book {
  display: flex;
  gap: 18px;
  padding: 20px;
  background: var(--web-surface);
  border: 1px solid var(--web-line);
  border-radius: 14px;
}
.explore-book .el-image {
  width: 90px;
  height: 130px;
  flex-shrink: 0;
  border-radius: 7px;
}
.explore-cover-fallback {
  display: grid;
  place-items: center;
  padding: 10px;
  height: 100%;
  background: var(--web-tint);
  color: var(--web-muted);
}
.explore-book-info {
  min-width: 0;
  flex: 1;
}
.explore-book h2 {
  margin: 0;
  font-size: 17px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.explore-book p {
  margin: 8px 0;
  font-size: 12px;
}
.explore-intro {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.7;
}
.explore-tags {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.explore-more {
  text-align: center;
  margin-top: 28px;
}
.explore-retry {
  margin: 12px 0;
}
</style>

<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">LEGADO READING SPACE</div>
      <h1>书籍详情</h1>
      <p>了解故事，再开始阅读。</p>
    </div>
    <el-button @click="goBack">返回{{ fromLabel }}</el-button>
  </div>
  <section v-loading="loading" class="book-detail" aria-label="书籍详情">
    <template v-if="book">
      <div class="detail-summary web-panel">
        <div class="detail-cover"><book-cover :book="book" /></div>
        <div class="detail-info">
          <div class="eyebrow">{{ shelfBook ? '我的书架' : '发现好书' }}</div>
          <h2>{{ book.name }}</h2>
          <p class="detail-author">{{ book.author || '佚名' }}</p>
          <div class="detail-tags">
            <span v-for="(tag, index) in kindTags" :key="`${index}:${tag}`">
              {{ tag }}
            </span>
            <span v-if="book.wordCount">{{ book.wordCount }}</span>
            <span v-if="shelfBook?.totalChapterNum">
              共 {{ shelfBook.totalChapterNum }} 章
            </span>
          </div>
          <p class="muted">书源：{{ book.originName || '本地书籍' }}</p>
          <p class="muted">
            最新章节：{{ book.latestChapterTitle || '暂无章节信息' }}
          </p>
          <div class="detail-actions">
            <el-button
              type="primary"
              :loading="busy === 'read'"
              :disabled="loading || !!busy"
              @click="readBook"
              >{{
                shelfBook?.durChapterTime ? '继续阅读' : '开始阅读'
              }}</el-button
            >
            <el-button
              v-if="!shelfBook"
              :loading="busy === 'add'"
              :disabled="loading || !!busy"
              @click="addToShelf"
              >加入书架</el-button
            >
            <span v-else class="muted">已在书架</span>
          </div>
          <p v-if="!shelfBook" class="detail-hint muted">
            开始阅读时会将这本书加入书架。
          </p>
        </div>
      </div>
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        show-icon
      />
      <el-button v-if="error" @click="loadDetail">重新加载</el-button>
      <section v-if="shelfBook" class="detail-section web-panel">
        <div class="group-heading">
          <h2>书籍分组</h2>
          <el-button
            v-if="!editingGroups && groupState"
            :disabled="loading || groupsLoading || !!busy"
            @click="editGroups"
            >编辑分组</el-button
          >
        </div>
        <p v-if="groupsLoading" class="muted">正在加载分组…</p>
        <template v-else-if="groupError">
          <p role="alert">{{ groupError }}</p>
          <el-button :disabled="!!busy" @click="loadGroups(generation)"
            >重新加载分组</el-button
          >
        </template>
        <template v-else-if="editingGroups && groupState">
          <el-checkbox-group
            v-model="selectedGroupIds"
            class="group-options"
            aria-label="选择书籍分组"
            :disabled="busy === 'groups'"
          >
            <el-checkbox
              v-for="group in groupState.groups"
              :key="group.groupId"
              :value="group.groupId"
              >{{ group.groupName }}</el-checkbox
            >
          </el-checkbox-group>
          <p class="muted">可同时选择多个分组，取消全部选择后为未分组。</p>
          <div class="group-actions">
            <el-button
              type="primary"
              :loading="busy === 'groups'"
              :disabled="!!busy"
              @click="saveGroups"
              >保存分组</el-button
            >
            <el-button :disabled="!!busy" @click="editingGroups = false"
              >取消</el-button
            >
          </div>
        </template>
        <template v-else-if="groupState">
          <div v-if="currentGroups.length" class="detail-tags">
            <span v-for="group in currentGroups" :key="group.groupId">
              {{ group.groupName }}
            </span>
          </div>
          <p v-else class="muted">未分组</p>
          <p v-if="!groupState.groups.length" class="muted">
            暂无自定义分组，可先在 App 中创建。
          </p>
        </template>
      </section>
      <section v-if="shelfBook" class="detail-section web-panel">
        <h2>阅读进度</h2>
        <p>已读：{{ shelfBook.durChapterTitle || '尚未开始阅读' }}</p>
        <el-progress :percentage="bookProgress(shelfBook)" :stroke-width="6" />
      </section>
      <section class="detail-section web-panel">
        <h2>内容简介</h2>
        <p class="detail-intro">{{ intro || '暂无简介' }}</p>
      </section>
    </template>
    <div v-else-if="!loading" class="web-empty">
      <h2>暂时无法加载书籍详情</h2>
      <p>{{ error || '书籍信息已失效，请返回列表重新选择。' }}</p>
      <el-button @click="loadDetail">重试</el-button>
      <el-button @click="goBack">返回{{ fromLabel }}</el-button>
    </div>
  </section>
</template>
<script setup lang="ts">
import API from '@api'
import type { BookGroupSelection } from '@api'
import type { Book } from '@/book'
import { bookProgress } from '@/utils/bookPresentation'
import { restoreDetailBook, startBookReading } from '@/utils/bookNavigation'

const route = useRoute(),
  router = useRouter(),
  store = useBookStore()
const queryString = (value: unknown) => (typeof value === 'string' ? value : '')
const url = computed(() => queryString(route.query.url))
const origin = computed(() => queryString(route.query.origin))
const from = computed(() =>
  route.query.from === 'search' || route.query.from === 'explore'
    ? route.query.from
    : 'shelf',
)
const fromLabel = computed(
  () => ({ search: '搜索', explore: '发现', shelf: '书架' })[from.value],
)
const goBack = () => {
  if (router.options.history.state.back) router.back()
  else void router.push({ name: from.value })
}
const snapshot = computed(() => restoreDetailBook(url.value, origin.value))
const shelfBook = computed(() =>
  store.shelf.find(book => book.bookUrl === url.value),
)
const book = computed(() => shelfBook.value || snapshot.value)
const kindTags = computed(() =>
  (book.value?.kind || '')
    .split(/[,，\r\n]+/)
    .map(tag => tag.trim())
    .filter(Boolean),
)
// 简介可能包含书源提供的 HTML，只显示文本，保留段落换行。
const intro = computed(() => {
  const current = book.value
  const text =
    (current && 'customIntro' in current ? current.customIntro : '') ||
    current?.intro ||
    ''
  const doc = new DOMParser().parseFromString(
    text.replace(/<br\s*\/?\s*>|<\/p>/gi, '\n'),
    'text/html',
  )
  doc.querySelectorAll('script, style').forEach(node => node.remove())
  return doc.body.textContent?.trim()
})
const loading = ref(true),
  error = ref(''),
  busy = ref<'read' | 'add' | 'groups' | ''>('')
const groupState = ref<BookGroupSelection>(),
  groupsLoading = ref(false),
  groupError = ref(''),
  editingGroups = ref(false),
  selectedGroupIds = ref<string[]>([])
const currentGroups = computed(
  () =>
    groupState.value?.groups.filter(group =>
      groupState.value?.selectedGroupIds.includes(group.groupId),
    ) || [],
)
let generation = 0
let disposed = false
const loadGroups = async (current: number) => {
  if (!shelfBook.value) return
  const bookUrl = url.value
  groupsLoading.value = true
  groupError.value = ''
  try {
    const { data } = await API.getBookGroup(bookUrl)
    if (disposed || current !== generation || bookUrl !== url.value) return
    if (!data.isSuccess) throw new Error(data.errorMsg || '分组加载失败')
    groupState.value = data.data
  } catch {
    if (!disposed && current === generation)
      groupError.value = '分组加载失败，请检查连接后重试。'
  } finally {
    if (!disposed && current === generation) groupsLoading.value = false
  }
}
const editGroups = () => {
  selectedGroupIds.value = [...(groupState.value?.selectedGroupIds || [])]
  editingGroups.value = true
}
const saveGroups = async () => {
  if (busy.value || loading.value || !shelfBook.value) return
  const current = generation,
    bookUrl = url.value
  busy.value = 'groups'
  try {
    const { data } = await API.saveBookGroups(bookUrl, selectedGroupIds.value)
    if (disposed || current !== generation) return
    if (!data.isSuccess) throw new Error(data.errorMsg || '保存分组失败')
    groupState.value = data.data
    editingGroups.value = false
    ElMessage.success('书籍分组已保存')
  } catch (e) {
    if (!disposed && current === generation)
      ElMessage.error(e instanceof Error ? e.message : '保存分组失败，请重试')
  } finally {
    if (!disposed && current === generation) busy.value = ''
  }
}
const loadDetail = async () => {
  const current = ++generation
  loading.value = true
  error.value = ''
  groupState.value = undefined
  groupError.value = ''
  groupsLoading.value = false
  editingGroups.value = false
  busy.value = ''
  try {
    await store.loadWebConfig()
    await store.loadBookShelf()
    if (!disposed && current === generation) await loadGroups(current)
  } catch {
    if (current === generation) error.value = '书架同步失败，请检查连接后重试。'
  } finally {
    if (current === generation) loading.value = false
  }
}
const ensureShelfBook = async (): Promise<Book> => {
  // 同步最新进度，避免重新添加已经在 App 中的书籍。
  const { data: shelf } = await API.getBookShelf()
  if (!shelf.isSuccess && !shelf.errorMsg?.includes('还没有添加小说'))
    throw new Error(shelf.errorMsg || '书架同步失败')
  store.shelf = shelf.isSuccess ? shelf.data : []
  if (disposed) throw new Error('详情页已关闭')
  if (shelfBook.value) return shelfBook.value
  if (!book.value) throw new Error('书籍信息已失效，请重新选择')
  const { data } = await API.saveBook(book.value)
  if (!data.isSuccess) throw new Error(data.errorMsg || '加入书架失败')
  await store.loadBookShelf()
  if (!shelfBook.value) throw new Error('书架同步失败，请重试')
  return shelfBook.value
}
const performAction = async (action: 'read' | 'add') => {
  if (busy.value || loading.value || !book.value) return
  busy.value = action
  try {
    const selected = await ensureShelfBook()
    if (disposed) return
    if (action === 'read') await startBookReading(router, selected)
    else {
      ElMessage.success('已加入书架')
      await loadGroups(generation)
    }
  } catch (e) {
    if (!disposed)
      ElMessage.error(e instanceof Error ? e.message : '操作失败，请重试')
  } finally {
    busy.value = ''
  }
}
const readBook = () => performAction('read')
const addToShelf = () => performAction('add')
watch([url, origin], loadDetail, { immediate: true })
watch(
  () => book.value?.name,
  name => {
    document.title = name ? `${name} · 书籍详情` : '书籍详情'
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  generation++
})
</script>
<style scoped>
.book-detail {
  min-height: 240px;
}
.detail-summary {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr);
  gap: 36px;
  padding: 32px;
  margin-bottom: 24px;
}
.detail-info {
  min-width: 0;
  overflow-wrap: anywhere;
}
.detail-info h2 {
  margin: 8px 0;
  font-size: 28px;
  line-height: 1.5;
}
.detail-author {
  margin: 8px 0 18px;
}
.detail-info > .muted,
.detail-hint {
  font-size: 13px;
  line-height: 1.7;
}
.detail-tags,
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.detail-tags span {
  color: var(--web-primary);
  background: var(--web-tint);
  border-radius: 6px;
  padding: 5px 9px;
  font-size: 12px;
}
.detail-actions {
  margin-top: 24px;
}
.detail-actions .el-button {
  margin: 0;
}
.detail-actions > span {
  font-size: 13px;
}
.detail-section {
  margin-top: 24px;
  padding: 28px 32px;
}
.detail-section h2 {
  font-size: 18px;
  margin: 0 0 18px;
}
.group-heading,
.group-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.group-heading {
  justify-content: space-between;
  margin-bottom: 18px;
}
.group-heading h2 {
  margin: 0;
}
.group-options {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 24px;
}
.group-options :deep(.el-checkbox) {
  margin: 0;
  max-width: 100%;
  height: auto;
  min-height: 32px;
}
.group-options :deep(.el-checkbox__label) {
  white-space: normal;
  overflow-wrap: anywhere;
}
.detail-section p {
  font-size: 14px;
  line-height: 1.9;
}
.detail-intro {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.detail-section :deep(.el-progress) {
  max-width: 420px;
}
@media (max-width: 600px) {
  .detail-summary {
    grid-template-columns: 90px minmax(0, 1fr);
    gap: 18px;
    padding: 20px;
  }
  .detail-info h2 {
    font-size: 21px;
  }
  .detail-section {
    padding: 20px;
  }
}
</style>

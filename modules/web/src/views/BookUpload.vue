<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">LEGADO READING SPACE</div>
      <h1>传书</h1>
      <p>把电脑上的书传到阅读 App，导入后即可在书架中阅读。</p>
    </div>
    <el-button @click="$router.push('/')" :disabled="busy">查看书架</el-button>
  </div>
  <section class="web-panel upload-panel">
    <label
      class="upload-drop"
      :class="{ 'is-dragging': dragging, 'is-busy': busy }"
      @dragover.prevent="dragging = !busy"
      @dragleave.prevent="dragging = false"
      @drop.prevent="dropFiles"
    >
      <el-icon class="upload-symbol"><UploadFilled /></el-icon>
      <strong>选择书籍，或拖拽文件到这里</strong>
      <span class="muted">支持批量选择，上传后自动导入 App</span>
      <span class="upload-formats"
        >TXT · EPUB · UMD · PDF · MOBI · AZW3 · AZW</span
      >
      <input
        type="file"
        multiple
        :accept="acceptedFormats"
        :disabled="busy"
        aria-label="选择书籍文件"
        @change="selectFiles"
      />
    </label>
    <div class="upload-toolbar">
      <span class="muted" role="status">{{ summary }}</span>
      <div class="inline-actions">
        <el-button :disabled="busy || !items.length" @click="items = []"
          >清空列表</el-button
        >
        <el-button
          type="primary"
          :disabled="busy || !pendingCount"
          :loading="busy"
          @click="startUpload"
        >
          {{ busy ? '正在传书' : '开始上传' }}
        </el-button>
      </div>
    </div>
    <ul v-if="items.length" class="upload-list" aria-label="传书列表">
      <li v-for="item in items" :key="item.id" class="upload-row">
        <el-icon class="file-symbol"><Document /></el-icon>
        <div class="upload-file">
          <strong :title="item.file.name">{{ item.file.name }}</strong>
          <small class="muted">{{ formatSize(item.file.size) }}</small>
          <el-progress
            v-if="item.status === 'uploading'"
            :percentage="item.percent"
            :stroke-width="4"
          />
          <p v-if="item.error" class="upload-error">{{ item.error }}</p>
        </div>
        <span class="upload-state" :class="item.status">{{
          statusText(item)
        }}</span>
        <el-button
          v-if="item.status === 'failed'"
          :disabled="busy"
          @click="retry(item)"
          >重试</el-button
        >
        <el-button
          v-else
          :disabled="busy"
          text
          :aria-label="`移除${item.file.name}`"
          @click="remove(item)"
          >移除</el-button
        >
      </li>
    </ul>
    <p v-else class="upload-empty">还没有选择书籍。</p>
  </section>
  <p class="upload-note muted">
    传输期间请保持阅读 App 的 Web 服务开启，并等待导入完成后再离开页面。
  </p>
</template>
<script setup lang="ts">
import { Document, UploadFilled } from '@element-plus/icons-vue'
import { onBeforeRouteLeave } from 'vue-router'
import { uploadBook } from '@/api/upload'

type UploadItem = {
  id: number
  file: File
  status: 'waiting' | 'uploading' | 'success' | 'failed'
  percent: number
  error: string
}
const acceptedFormats = '.txt,.epub,.umd,.pdf,.mobi,.azw3,.azw'
const items = ref<UploadItem[]>([])
const busy = ref(false)
const dragging = ref(false)
let nextId = 0
let controller: AbortController | undefined
const pendingCount = computed(
  () => items.value.filter(item => item.status === 'waiting').length,
)
const summary = computed(() => {
  const success = items.value.filter(item => item.status === 'success').length
  const failed = items.value.filter(item => item.status === 'failed').length
  return `共 ${items.value.length} 个文件 · 已导入 ${success} 个${failed ? ` · 失败 ${failed} 个` : ''}`
})
const addFiles = (files: File[]) => {
  if (busy.value) return
  let unsupported = 0
  for (const file of files) {
    const extension = '.' + file.name.split('.').pop()?.toLowerCase()
    if (!acceptedFormats.split(',').includes(extension)) {
      unsupported++
      continue
    }
    if (
      items.value.some(
        item =>
          item.file.name === file.name &&
          item.file.size === file.size &&
          item.file.lastModified === file.lastModified,
      )
    )
      continue
    items.value.push({
      id: nextId++,
      file,
      status: 'waiting',
      percent: 0,
      error: '',
    })
  }
  if (unsupported)
    ElMessage.warning(`已跳过 ${unsupported} 个不支持的文件，请选择书籍文件。`)
}
const selectFiles = (event: Event) => {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files ?? []))
  input.value = ''
}
const dropFiles = (event: DragEvent) => {
  dragging.value = false
  addFiles(Array.from(event.dataTransfer?.files ?? []))
}
const startUpload = async () => {
  if (busy.value || !pendingCount.value) return
  busy.value = true
  controller = new AbortController()
  try {
    for (const item of items.value.filter(item => item.status === 'waiting')) {
      if (controller.signal.aborted) break
      item.status = 'uploading'
      item.error = ''
      try {
        const { data } = await uploadBook(
          item.file,
          controller.signal,
          percent => {
            item.percent = percent
          },
        )
        if (!data.isSuccess) throw new Error(data.errorMsg || '导入失败')
        item.status = 'success'
        item.percent = 100
      } catch (error) {
        item.status = 'failed'
        item.error =
          error instanceof Error && error.message
            ? error.message
            : '上传失败，请检查连接后重试'
      }
    }
  } finally {
    busy.value = false
    controller = undefined
  }
}
const retry = (item: UploadItem) => {
  if (busy.value) return
  item.status = 'waiting'
  item.percent = 0
  item.error = ''
  void startUpload()
}
const remove = (item: UploadItem) => {
  if (!busy.value)
    items.value = items.value.filter(candidate => candidate.id !== item.id)
}
const statusText = (item: UploadItem) =>
  ({
    waiting: '等待上传',
    uploading: item.percent >= 100 ? '导入中' : '上传中',
    success: '已导入',
    failed: '失败',
  })[item.status]
const formatSize = (bytes: number) =>
  bytes < 1024
    ? `${bytes} B`
    : bytes < 1024 * 1024
      ? `${(bytes / 1024).toFixed(1)} KB`
      : `${(bytes / 1024 / 1024).toFixed(1)} MB`
onBeforeRouteLeave(() => {
  if (busy.value) {
    ElMessage.info('正在传书，请等待导入完成后再切换页面。')
    return false
  }
})
onBeforeUnmount(() => controller?.abort())
</script>
<style scoped>
.upload-panel {
  padding: 24px;
}
.upload-drop {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 240px;
  padding: 28px 20px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
  text-align: center;
  cursor: pointer;
  transition:
    border-color 0.2s,
    background 0.2s;
}
.upload-drop:hover,
.upload-drop:focus-within,
.upload-drop.is-dragging {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
}
.upload-drop input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}
.upload-drop.is-busy,
.upload-drop input:disabled {
  cursor: wait;
}
.upload-symbol {
  font-size: 48px;
  color: var(--el-color-primary);
}
.upload-drop strong {
  font-size: 18px;
}
.upload-formats {
  font-size: 12px;
  color: var(--web-muted);
}
.upload-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 0;
  flex-wrap: wrap;
}
.upload-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.upload-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 0;
  border-top: 1px solid var(--web-line);
}
.file-symbol {
  flex-shrink: 0;
  font-size: 24px;
  color: var(--web-muted);
}
.upload-file {
  flex: 1;
  min-width: 0;
}
.upload-file strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.upload-file small {
  display: block;
  margin: 6px 0;
}
.upload-state {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--web-muted);
}
.upload-state.success {
  color: var(--el-color-success);
}
.upload-state.failed,
.upload-error {
  color: var(--el-color-danger);
}
.upload-error {
  margin: 8px 0 0;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.upload-empty {
  margin: 0;
  padding: 24px;
  border-top: 1px solid var(--web-line);
  text-align: center;
  color: var(--web-muted);
}
.upload-note {
  font-size: 12px;
  line-height: 1.8;
}
@media (max-width: 600px) {
  .upload-panel {
    padding: 16px;
  }
  .upload-drop {
    min-height: 220px;
    padding: 24px 12px;
  }
  .upload-drop strong {
    font-size: 16px;
  }
  .upload-row {
    gap: 8px;
  }
  .file-symbol {
    display: none;
  }
  .upload-row :deep(.el-button) {
    padding: 8px;
  }
}
</style>

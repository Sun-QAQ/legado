<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">LEGADO READING SPACE</div>
      <h1>{{ isBook ? '书源管理' : '订阅源管理' }}</h1>
      <p>在一个工作区完成规则编辑、同步和调试。</p>
    </div>
    <span class="source-status" role="status">{{
      store.isDirty ? '有未保存修改' : '编辑器已就绪'
    }}</span>
  </div>
  <div class="source-workspace">
    <aside class="source-rail web-panel">
      <h2>
        源列表 <small>{{ store.sources.length }}</small>
      </h2>
      <source-list /><el-button class="new-source" @click="newSource"
        >＋ 新建源</el-button
      >
    </aside>
    <section class="source-editor web-panel">
      <div class="source-title">
        <h2>{{ getSourceName(store.currentSource) || '未命名源' }}</h2>
        <small class="muted">完整规则字段 · 支持表单与 JSON 编辑</small>
      </div>
      <tool-bar /><source-tab-tools :config="config" />
    </section>
  </div>
</template>
<script setup lang="ts">
import bookSourceConfig from '@/config/bookSourceEditConfig'
import rssSourceConfig from '@/config/rssSourceEditConfig'
import type { SourceConfig } from '@/config/sourceConfig'
import { sourceMode } from '@/config/sourceContext'
import { getSourceName } from '@/utils/souce'
import '@/assets/sourceeditor.css'
const store = useSourceStore()
store.selectType()
watch(
  () => JSON.stringify(store.currentSource),
  (_, previous) => {
    if (previous) store.recordHistory(previous)
  },
  { flush: 'sync' },
)
const isBook = computed(() => sourceMode.value === 'book')
const config = computed(
  () => (isBook.value ? bookSourceConfig : rssSourceConfig) as SourceConfig,
)
const newSource = async () => {
  if (store.isDirty) {
    try {
      await ElMessageBox.confirm(
        '当前修改尚未保存，是否放弃并新建源？',
        '新建源',
        { confirmButtonText: '放弃并新建', cancelButtonText: '继续编辑' },
      )
    } catch {
      return
    }
  }
  store.clearEdit()
  store.changeTabName('editForm')
}
onBeforeRouteLeave(async () => {
  if (!store.isDirty) return true
  try {
    await ElMessageBox.confirm('当前源有未保存修改，是否离开？', '离开编辑器', {
      confirmButtonText: '离开',
      cancelButtonText: '继续编辑',
      closeOnHashChange: false,
    })
    return true
  } catch {
    return false
  }
})
watchEffect(() => {
  document.title = isBook.value ? '书源管理 · 阅读' : '订阅源管理 · 阅读'
})
</script>
<style scoped>
.source-workspace {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.source-status {
  color: var(--web-primary);
  font-size: 12px;
  background: var(--web-tint);
  padding: 6px 10px;
  border-radius: 6px;
}
.source-rail {
  padding: 18px;
  position: sticky;
  top: 20px;
}
.source-rail h2 {
  font-size: 16px;
}
.source-rail h2 small {
  font-size: 12px;
  color: var(--web-muted);
}
.source-editor {
  padding: 22px;
}
.source-title {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.source-title h2 {
  overflow-wrap: anywhere;
}
.new-source {
  margin-top: 14px;
  width: 100%;
}
@media (max-width: 1100px) {
  .source-workspace {
    grid-template-columns: 220px minmax(0, 1fr);
  }
}
@media (max-width: 900px) {
  .source-workspace {
    grid-template-columns: 1fr;
  }
  .source-rail {
    position: static;
  }
  .source-editor {
    padding: 18px;
  }
}
</style>

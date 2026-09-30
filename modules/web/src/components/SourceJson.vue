<template>
  <el-alert
    v-if="store.jsonError"
    :title="store.jsonError"
    type="error"
    :closable="false"
    show-icon
  />
  <label for="source-json" class="muted"
    >直接编辑源 JSON，合法内容会同步到表单。</label
  >
  <el-input
    id="source-json"
    v-model="sourceString"
    type="textarea"
    :rows="20"
    spellcheck="false"
    aria-label="源 JSON"
    @input="update"
  />
</template>
<script setup lang="ts">
import type { Source } from '@/source'
const store = useSourceStore()
const sourceString = ref('')
let applying = false
const update = (value: string) => {
  try {
    const source = JSON.parse(value)
    if (!source || typeof source !== 'object' || Array.isArray(source))
      throw new Error('源必须是 JSON 对象')
    if (
      (store.editorKind === 'book' &&
        ('sourceUrl' in source || 'sourceName' in source)) ||
      (store.editorKind === 'rss' &&
        ('bookSourceUrl' in source || 'bookSourceName' in source))
    )
      throw new Error('源类型与当前编辑器不一致')
    applying = true
    store.changeCurrentSource(source as Source, false)
    store.changeEditTabSource(store.currentSource)
    store.jsonError = ''
  } catch (error) {
    store.jsonError =
      error instanceof SyntaxError
        ? 'JSON 格式错误，请修正后再保存或调试。'
        : (error as Error).message
  } finally {
    applying = false
  }
}
watch(
  () => store.currentSource,
  source => {
    if (!applying) sourceString.value = JSON.stringify(source, null, 2)
  },
  { deep: true, immediate: true, flush: 'sync' },
)
</script>
<style scoped>
label {
  display: block;
  font-size: 12px;
  margin: 12px 0;
}
.el-alert {
  margin-bottom: 14px;
}
:deep(.el-textarea__inner) {
  font:
    13px/1.8 Consolas,
    monospace;
  min-height: 420px;
}
</style>

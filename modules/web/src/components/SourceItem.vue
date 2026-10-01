<template>
  <el-checkbox
    size="large"
    border
    :value="sourceUrl"
    :class="{
      error: isSaveError,
      edit: sourceUrl == currentSourceUrl,
    }"
  >
    <span class="source-name" :title="getSourceName(source)">{{
      getSourceName(source)
    }}</span>
    <el-button
      class="source-edit"
      text
      :icon="Edit"
      :aria-label="`编辑源${getSourceName(source)}`"
      @click.stop="handleSourceClick(source)"
    />
  </el-checkbox>
</template>

<script setup lang="ts">
import { Edit } from '@element-plus/icons-vue'
import { getSourceUniqueKey, getSourceName } from '@/utils/souce'
import type { Source } from '@/source'

const props = defineProps<{
  source: Source
}>()

const store = useSourceStore()

const currentSourceUrl = computed(() => store.currentSourceUrl)
const sourceUrl = computed(() => getSourceUniqueKey(props.source))

const handleSourceClick = async (source: Source) => {
  if (store.isDirty) {
    try {
      await ElMessageBox.confirm('当前修改尚未保存，是否切换源？', '切换源', {
        confirmButtonText: '切换',
        cancelButtonText: '继续编辑',
      })
    } catch {
      return
    }
  }
  store.changeCurrentSource(source)
  store.changeTabName('editForm')
}
const isSaveError = computed(() => {
  const map = store.savedSourcesMap
  if (map.size == 0) return false
  return !map.has(sourceUrl.value)
})
</script>
<style lang="scss" scoped>
:deep(.el-checkbox__label) {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}
.source-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-edit {
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  padding: 0;
  margin-left: 0;
}
.error {
  border-color: var(--el-color-error) !important;
  color: var(--el-color-error) !important;
  --el-checkbox-checked-text-color: var(--el-color-error);
  --el-checkbox-checked-bg-color: var(--el-color-error);
  --el-checkbox-checked-input-border-color: var(--el-color-error);
}
.edit {
  border-color: var(--el-color-dark) !important;
}
</style>

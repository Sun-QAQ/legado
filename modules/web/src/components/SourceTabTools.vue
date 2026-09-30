<template>
  <el-tabs v-model="currentTab" class="source-tabs"
    ><el-tab-pane label="表单编辑" name="editForm"
      ><source-tab-form :config="config" /></el-tab-pane
    ><el-tab-pane label="JSON" name="editTab"><source-json /></el-tab-pane
    ><el-tab-pane label="调试输出" name="editDebug"
      ><source-debug /></el-tab-pane
    ><el-tab-pane label="规则帮助" name="editHelp"><source-help /></el-tab-pane
  ></el-tabs>
</template>
<script setup lang="ts">
import type { SourceConfig } from '@/config/sourceConfig'
defineProps<{ config: SourceConfig }>()
const store = useSourceStore()
const currentTab = computed({
  get: () => store.currentTab,
  set: val => store.changeTabName(val),
})
</script>
<style scoped>
.source-tabs {
  min-width: 0;
}
.source-tabs :deep(.el-tabs__content) {
  overflow: visible;
}
.source-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
}
</style>

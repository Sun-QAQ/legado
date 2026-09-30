<template>
  <el-tabs id="source-edit"
    ><el-tab-pane
      v-for="section in Object.values(config)"
      :label="section.name"
      :key="section.name"
      ><el-form label-position="top"
        ><div class="source-fields">
          <source-field
            v-for="field in section.children.filter(
              field => !advanced(section.name, field.id),
            )"
            :key="field.id"
            :field="field"
          />
        </div>
        <details v-if="section.name === '基础'" class="advanced-fields">
          <summary>高级选项 · 登录、请求头、JS 库与其他配置</summary>
          <source-field
            v-for="field in section.children.filter(field =>
              advanced(section.name, field.id),
            )"
            :key="field.id"
            :field="field"
          /></details></el-form></el-tab-pane
  ></el-tabs>
</template>
<script setup lang="ts">
import type { SourceConfig } from '@/config/sourceConfig'
defineProps<{ config: SourceConfig }>()
const common = new Set([
  'bookSourceType',
  'bookSourceUrl',
  'bookSourceName',
  'bookSourceGroup',
  'bookSourceComment',
  'sourceUrl',
  'sourceName',
  'sourceGroup',
  'sourceComment',
  'sourceIcon',
  'sortUrl',
])
const advanced = (section: string, id: string) =>
  section === '基础' && !common.has(id)
</script>
<style scoped>
.source-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
  padding-top: 15px;
}
.advanced-fields {
  margin: 10px 0 18px;
}
.advanced-fields summary {
  color: var(--web-muted);
  cursor: pointer;
  font-size: 12px;
  padding: 14px 0;
  border-top: 1px solid var(--web-line);
}
.advanced-fields :deep(.el-form-item) {
  margin-top: 16px;
}
:deep(.el-form-item__label) {
  font-size: 12px;
  color: var(--web-muted);
}
:deep(.el-select) {
  width: 100%;
}
@media (max-width: 1100px) {
  .source-fields {
    grid-template-columns: 1fr;
  }
}
</style>

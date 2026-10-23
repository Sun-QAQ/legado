<template>
  <el-form-item :label="field.title" :required="field.required"
    ><source-code-editor
      v-if="field.type === 'String'"
      v-model="stringValue"
      :language="sourceLanguageForField(field.id)"
      :aria-label="field.title"
      :placeholder="field.hint" /><el-switch
      v-else-if="field.type === 'Boolean'"
      v-model="booleanValue" /><el-input-number
      v-else-if="field.type === 'Number'"
      v-model="numberValue"
      :min="0" /><el-select
      v-else-if="field.type === 'Array'"
      v-model="numberValue"
      ><el-option
        v-for="(name, index) in field.array"
        :key="name"
        :value="index"
        :label="name" /></el-select
  ></el-form-item>
</template>
<script setup lang="ts">
import type { SourceConfigRecord } from '@/config/sourceConfig'
import SourceCodeEditor from './SourceCodeEditor.vue'
import { sourceLanguageForField } from '@/utils/sourceSyntax'
const props = defineProps<{ field: SourceConfigRecord }>()
const store = useSourceStore()
const target = (create = false): Record<string, unknown> => {
  const source = store.currentSource as unknown as Record<string, unknown>
  const namespace = props.field.namespace
  if (!namespace) return source
  if (
    !source[namespace] ||
    typeof source[namespace] !== 'object' ||
    Array.isArray(source[namespace])
  ) {
    if (create) source[namespace] = {}
    else return {}
  }
  return source[namespace] as Record<string, unknown>
}
const update = (value: unknown) => {
  target(true)[props.field.id] = value
  store.jsonError = ''
}
const stringValue = computed({
  get: () => String(target()[props.field.id] ?? ''),
  set: update,
})
const numberValue = computed({
  get: () => {
    const value = target()[props.field.id]
    return typeof value === 'number' ? value : undefined
  },
  set: update,
})
const booleanValue = computed({
  get: () => Boolean(target()[props.field.id]),
  set: update,
})
</script>

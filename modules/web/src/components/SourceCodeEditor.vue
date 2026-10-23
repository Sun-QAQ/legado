<template>
  <div class="source-code-editor" :class="{ composing }">
    <pre
      ref="paint"
      aria-hidden="true"
    ><template v-for="(part, index) in parts" :key="index"><span :class="part.kind && `syntax-${part.kind}`">{{ part.text }}</span></template>{{ '\n' }}</pre>
    <textarea
      ref="input"
      v-model="model"
      v-bind="$attrs"
      :rows="rows"
      :placeholder="placeholder"
      spellcheck="false"
      autocapitalize="off"
      autocomplete="off"
      autocorrect="off"
      @input="onInput"
      @scroll="syncScroll"
      @compositionstart="composing = true"
      @compositionend="compositionEnd"
    />
  </div>
</template>
<script setup lang="ts">
import {
  tokenizeSource,
  type SourceLanguage,
  type TokenKind,
  type SyntaxToken,
} from '@/utils/sourceSyntax'

defineOptions({ inheritAttrs: false })
const props = withDefaults(
  defineProps<{
    language?: SourceLanguage
    placeholder?: string
    rows?: number
  }>(),
  { language: 'rule', placeholder: '', rows: 2 },
)
const model = defineModel<string>({ required: true })
const emit = defineEmits<{ input: [value: string] }>()
const input = ref<HTMLTextAreaElement>()
const paint = ref<HTMLPreElement>()
const composing = ref(false)
const rendered = shallowRef({ text: '', tokens: [] as SyntaxToken[] })
let timer: ReturnType<typeof setTimeout> | undefined
let observer: ResizeObserver | undefined

const parts = computed(() => {
  const text = model.value
  if (rendered.value.text !== text || composing.value)
    return [{ text, kind: '' }]
  const result: { text: string; kind: TokenKind | '' }[] = []
  let start = 0
  for (const token of rendered.value.tokens) {
    if (token.start > start)
      result.push({ text: text.slice(start, token.start), kind: '' })
    result.push({ text: text.slice(token.start, token.end), kind: token.kind })
    start = token.end
  }
  if (start < text.length) result.push({ text: text.slice(start), kind: '' })
  return result
})
const syncScroll = () => {
  if (paint.value && input.value) {
    paint.value.scrollTop = input.value.scrollTop
    paint.value.scrollLeft = input.value.scrollLeft
  }
}
const resize = () => {
  if (!input.value) return
  const top = input.value.scrollTop
  input.value.style.height = 'auto'
  input.value.style.height = `${Math.min(600, input.value.scrollHeight + 2)}px`
  input.value.scrollTop = top
  syncScroll()
}
const schedule = () => {
  clearTimeout(timer)
  if (composing.value) return
  timer = setTimeout(() => {
    rendered.value = {
      text: model.value,
      tokens: tokenizeSource(model.value, props.language),
    }
    nextTick(syncScroll)
  }, 150)
}
const onInput = (event: Event) => {
  if (!(event as InputEvent).isComposing && !composing.value)
    emit('input', (event.target as HTMLTextAreaElement).value)
}
const compositionEnd = (event: CompositionEvent) => {
  composing.value = false
  // Vue dispatches the final input event for v-model after composition ends.
  nextTick(() => {
    emit('input', (event.target as HTMLTextAreaElement).value)
    schedule()
  })
}
watch(
  [model, () => props.language],
  () => {
    schedule()
    nextTick(resize)
  },
  { immediate: true },
)
onMounted(() => {
  resize()
  let width = 0
  observer = new ResizeObserver(entries => {
    const next = entries[0].contentRect.width
    if (next !== width) {
      width = next
      resize()
    }
  })
  if (input.value) observer.observe(input.value)
})
onBeforeUnmount(() => {
  clearTimeout(timer)
  observer?.disconnect()
})
</script>
<style scoped>
.source-code-editor {
  --syntax-keyword: #7040a0;
  --syntax-string: #276339;
  --syntax-comment: #66717d;
  --syntax-number: #995214;
  --syntax-rule: #a34410;
  --syntax-property: #145d9a;
  --syntax-operator: #526073;
  position: relative;
  width: 100%;
  background: var(--web-surface, var(--el-fill-color-blank));
  border-radius: var(--el-input-border-radius, 4px);
}
:global(html.dark .source-code-editor) {
  --syntax-keyword: #c4a7ff;
  --syntax-string: #a5d6a7;
  --syntax-comment: #a3adb8;
  --syntax-number: #ffc58f;
  --syntax-rule: #ffbc80;
  --syntax-property: #91caff;
  --syntax-operator: #bac6d6;
}
pre,
textarea {
  box-sizing: border-box;
  width: 100%;
  margin: 0;
  padding: 9px 12px;
  border: 1px solid transparent;
  font:
    13px/1.8 Consolas,
    'Courier New',
    monospace;
  letter-spacing: normal;
  tab-size: 4;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: normal;
  text-align: left;
  overflow: auto;
  scrollbar-gutter: stable;
}
pre {
  position: absolute;
  inset: 0;
  height: 100%;
  color: var(--web-text, var(--el-text-color-primary));
  pointer-events: none;
  scrollbar-color: transparent transparent;
}
pre::-webkit-scrollbar {
  visibility: hidden;
}
textarea {
  position: relative;
  display: block;
  min-height: 66px;
  max-height: 600px;
  resize: none;
  color: transparent;
  -webkit-text-fill-color: transparent;
  caret-color: var(--web-text, var(--el-text-color-primary));
  background: transparent;
  border-color: var(--el-border-color);
  border-radius: inherit;
  outline: none;
}
textarea:focus {
  border-color: var(--el-color-primary);
}
textarea::placeholder {
  color: var(--el-text-color-placeholder);
  -webkit-text-fill-color: var(--el-text-color-placeholder);
}
textarea::selection {
  background: rgb(108 92 231 / 25%);
}
.composing textarea {
  color: var(--web-text, var(--el-text-color-primary));
  -webkit-text-fill-color: currentColor;
}
.composing pre {
  visibility: hidden;
}
.syntax-keyword {
  color: var(--syntax-keyword);
}
.syntax-string {
  color: var(--syntax-string);
}
.syntax-comment {
  color: var(--syntax-comment);
}
.syntax-number {
  color: var(--syntax-number);
}
.syntax-rule {
  color: var(--syntax-rule);
}
.syntax-property {
  color: var(--syntax-property);
}
.syntax-operator {
  color: var(--syntax-operator);
}
@media (forced-colors: active) {
  pre {
    display: none;
  }
  textarea {
    color: CanvasText;
    -webkit-text-fill-color: CanvasText;
  }
}
</style>

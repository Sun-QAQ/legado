<template>
  <div class="book-cover" :style="{ '--cover-color': color }">
    <img
      v-if="src && !failed"
      :src="src"
      :alt="`${book.name}封面`"
      loading="lazy"
      @error="onError"
    />
    <div v-else class="cover-fallback" aria-hidden="true">
      <span>{{ book.name }}</span
      ><small>{{ book.author || '阅读' }}</small>
    </div>
  </div>
</template>
<script setup lang="ts">
import type { Book, SeachBook } from '@/book'
import API from '@api'
const props = defineProps<{ book: Book | SeachBook }>()
const failed = ref(false)
const original = computed(
  () =>
    ('customCoverUrl' in props.book ? props.book.customCoverUrl : '') ||
    props.book.coverUrl,
)
const src = computed(() =>
  original.value ? API.getProxyCoverUrl(original.value) : '',
)
watch(
  () => [props.book.bookUrl, original.value],
  () => {
    failed.value = false
  },
)
const onError = () => {
  failed.value = true
}
const color = computed(() => {
  const palette = [
    '#586d6b',
    '#555268',
    '#99846a',
    '#52647c',
    '#9a6c62',
    '#72715b',
  ]
  let hash = 0
  for (const c of props.book.name)
    hash = (hash + c.charCodeAt(0)) % palette.length
  return palette[hash]
})
</script>
<style scoped>
.book-cover {
  width: 100%;
  aspect-ratio: 0.72;
  border-radius: 3px 8px 8px 3px;
  overflow: hidden;
  background: var(--cover-color);
  box-shadow: 4px 7px 12px #17131f17;
}
.book-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.cover-fallback {
  height: 100%;
  position: relative;
  padding: 16px;
  border-left: 5px solid #ffffff29;
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.cover-fallback:before {
  content: '';
  position: absolute;
  inset: 10px;
  border: 1px solid #ffffff36;
  pointer-events: none;
}
.cover-fallback span {
  writing-mode: vertical-rl;
  font:
    24px/1.5 SimSun,
    serif;
  letter-spacing: 3px;
  max-height: 80%;
  overflow: hidden;
}
.cover-fallback small {
  font-size: 11px;
  color: #ffffffce;
  position: relative;
}
.book-cover:hover {
  box-shadow: 4px 9px 16px #17131f24;
}
</style>

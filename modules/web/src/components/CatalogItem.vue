<template>
  <div class="wrapper">
    <button
      type="button"
      v-for="cata in catas"
      class="cata-text"
      :key="cata.url"
      :class="{ selected: isSelected(cata.index) }"
      @click="gotoChapter(cata)"
    >
      {{ cata.title }}
    </button>
  </div>
</template>
<script setup lang="ts">
import type { BookChapter } from '@/book'

const props = defineProps<{
  index: number
  source: BookChapter | { index: number; catas: BookChapter[] }
  gotoChapter: (chapter: BookChapter) => void
  currentChapterIndex: number
}>()

const isSelected = (idx: number) => {
  return idx == props.currentChapterIndex
}

// PC端 一个虚拟列表中有两个章节
const catas = computed(() => {
  const source = props.source
  if ('catas' in source) return source.catas
  return [props.source as BookChapter]
})
</script>

<style lang="scss" scoped>
.selected {
  color: var(--web-primary) !important;
}
.wrapper {
  display: flex;

  .cata-text {
    color: var(--web-text);
    background: transparent;
    border: 0;
    cursor: pointer;
    padding: 10px 0;
    text-align: left;
    font: inherit;
    width: 100%;
    margin-right: 26px;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}
</style>

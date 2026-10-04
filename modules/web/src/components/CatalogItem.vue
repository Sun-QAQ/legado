<template>
  <div class="wrapper">
    <button
      type="button"
      class="cata-text"
      :class="{ selected: source.index === currentChapterIndex }"
      @click="gotoChapter(source)"
    >
      {{ source.title }}
    </button>
  </div>
</template>
<script setup lang="ts">
import type { BookChapter } from '@/book'

defineProps<{
  index: number
  source: BookChapter
  gotoChapter: (chapter: BookChapter) => void
  currentChapterIndex: number
}>()
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
    height: 40px;
    padding: 0 12px 0 0;
    text-align: left;
    font: inherit;
    width: 100%;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}
</style>

<template>
  <section class="manga-reader" aria-label="漫画阅读">
    <div
      class="manga-stage"
      @touchstart.passive="onTouchStart"
      @touchend.passive="onTouchEnd"
    >
      <img
        v-if="currentPage && !failed"
        :key="imageUrl"
        class="manga-image"
        :src="imageUrl"
        :alt="`${title} · 第 ${pageIndex + 1} 页`"
        @error="failed = true"
        draggable="false"
      />
      <div v-else-if="failed" class="manga-empty">
        <p>图片加载失败</p>
        <el-button @click="retryImage">重试</el-button>
      </div>
      <div v-else class="manga-empty">
        本章没有图片，可切换章节或关闭漫画模式阅读正文。
      </div>
      <el-button
        class="manga-previous"
        aria-label="上一页漫画"
        :disabled="busy || !canPrevious"
        @click="turnPage(-1)"
        >←</el-button
      >
      <el-button
        class="manga-next"
        aria-label="下一页漫画"
        :disabled="busy || !canNext"
        @click="turnPage(1)"
        >→</el-button
      >
    </div>
    <div class="manga-caption">
      <span>{{ title }}</span
      ><span>{{
        pages.length ? `${pageIndex + 1} / ${pages.length} 页` : '0 / 0 页'
      }}</span>
    </div>
  </section>
</template>
<script setup lang="ts">
import API from '@api'
import type { MangaPage } from '@/utils/mangaPages'

const props = defineProps<{
  pages: MangaPage[]
  position: number
  title: string
  bookUrl: string
  imageWidth: number
  busy: boolean
  hasPreviousChapter: boolean
  hasNextChapter: boolean
}>()
const emit = defineEmits<{
  progress: [position: number]
  previousChapter: []
  nextChapter: []
}>()
const pageIndex = computed(() => {
  const next = props.pages.findIndex(page => page.chapterPos > props.position)
  return next < 0 ? Math.max(0, props.pages.length - 1) : Math.max(0, next - 1)
})
const currentPage = computed(() => props.pages[pageIndex.value])
const imageUrl = computed(() =>
  currentPage.value
    ? API.getProxyImageUrl(
        props.bookUrl,
        currentPage.value.src,
        props.imageWidth,
      )
    : '',
)
const failed = ref(false)
const canPrevious = computed(
  () => pageIndex.value > 0 || props.hasPreviousChapter,
)
const canNext = computed(
  () => pageIndex.value < props.pages.length - 1 || props.hasNextChapter,
)
watch(imageUrl, () => {
  failed.value = false
})
// 将恢复的文本进度对齐到当前图片，保持 App 与 Web 使用同一位置单位。
onMounted(() => {
  if (currentPage.value) emit('progress', currentPage.value.chapterPos)
})
const turnPage = (direction: -1 | 1) => {
  if (props.busy) return
  const next = props.pages[pageIndex.value + direction]
  if (next) emit('progress', next.chapterPos)
  else if (direction < 0 && props.hasPreviousChapter) emit('previousChapter')
  else if (direction > 0 && props.hasNextChapter) emit('nextChapter')
}
const retryImage = () => {
  failed.value = false
}
let touchStart: { x: number; y: number } | undefined
const onTouchStart = (event: TouchEvent) => {
  if (event.touches.length !== 1) {
    touchStart = undefined
    return
  }
  touchStart = { x: event.touches[0].clientX, y: event.touches[0].clientY }
}
const onTouchEnd = (event: TouchEvent) => {
  const start = touchStart
  touchStart = undefined
  if (!start || event.changedTouches.length !== 1) return
  const dx = event.changedTouches[0].clientX - start.x
  const dy = event.changedTouches[0].clientY - start.y
  if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.3)
    turnPage(dx < 0 ? 1 : -1)
}
defineExpose({ turnPage })
</script>
<style scoped>
.manga-stage {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: max(240px, calc(100dvh - 220px));
  touch-action: pan-y pinch-zoom;
}
.manga-image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.manga-stage .el-button {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  margin: 0;
  width: 40px;
  height: 48px;
  padding: 0;
  background: var(--web-surface);
  opacity: 0.85;
}
.manga-previous {
  left: 4px;
}
.manga-next {
  right: 4px;
}
.manga-caption {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 8px;
  font-size: 12px;
}
.manga-caption span:first-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.manga-caption span:last-child {
  flex-shrink: 0;
}
.manga-empty {
  padding: 24px 52px;
  text-align: center;
}
.manga-empty .el-button {
  position: static;
  transform: none;
  width: auto;
  height: auto;
  padding: 8px 16px;
}
@media (max-width: 600px) {
  .manga-stage {
    height: max(240px, calc(100dvh - 265px));
  }
}
</style>

<template>
  <div :class="{ 'cata-wrapper': true, visible: popCataVisible }">
    <div class="title">目录</div>
    <virtual-list
      style="height: min(65vh, 600px); overflow: auto"
      :class="{ night: isNight, day: !isNight }"
      ref="virtualListRef"
      data-key="index"
      wrap-class="data-wrapper"
      item-class="cata"
      :data-sources="catalog"
      :data-component="CatalogItem"
      :estimate-size="40"
      :extra-props="{ gotoChapter, currentChapterIndex }"
    />
  </div>
</template>

<script setup lang="ts">
import VirtualList from 'vue3-virtual-scroll-list'
import '../assets/fonts/popfont.css'
import CatalogItem from './CatalogItem.vue'
import type { BookChapter } from '@/book'

const store = useBookStore()

const { catalog, popCataVisible } = storeToRefs(store)

//主题
const isNight = computed(() => store.isNight)
//打开目录 计算当前章节对应的虚拟列表位置
const virtualListRef = ref()
const currentChapterIndex = computed({
  get: () => store.readingBook.chapterIndex,
  set: value => (store.readingBook.chapterIndex = value),
})
onUpdated(() => {
  // dom更新触发ResizeObserver，更新虚拟列表内部的sizes Map
  if (!popCataVisible.value) return
  virtualListRef.value.scrollToIndex(currentChapterIndex.value)
})

// 点击加载对应章节内容
const emit = defineEmits(['getContent'])
const gotoChapter = (chapter: BookChapter) => {
  const chapterIndex = catalog.value.indexOf(chapter)
  currentChapterIndex.value = chapterIndex
  store.setPopCataVisible(false)
  store.setContentLoading(true)
  store.saveBookProgress()
  emit('getContent', chapterIndex)
}
</script>

<style lang="scss" scoped>
.cata-wrapper {
  margin: -16px;
  padding: 18px 0 24px 25px;

  /* background: #ede7da url('../assets/imgs/themes/popup_1.png') repeat; */
  .title {
    font-size: 18px;
    font-weight: 400;
    font-family: FZZCYSK;
    margin: 0 0 20px 0;
    color: #ed4259;
    width: fit-content;
    border-bottom: 1px solid #ed4259;
  }
  :deep(.data-wrapper) {
    .cata {
      height: 40px;
      cursor: pointer;
      font:
        16px / 40px PingFangSC-Regular,
        HelveticaNeue-Light,
        'Helvetica Neue Light',
        'Microsoft YaHei',
        sans-serif;
    }
  }

  .night {
    :deep(.cata) {
      border-bottom: 1px solid #666;
    }
  }

  .day {
    :deep(.cata) {
      border-bottom: 1px solid #f2f2f2;
    }
  }
}
</style>

<style scoped>
.cata-wrapper {
  margin: 0;
  padding: 0;
  background: transparent !important;
  width: 100%;
  color: var(--web-text);
}
.cata-wrapper .title {
  font-family: inherit;
  margin: 0 0 18px;
  font-size: 14px;
}
</style>

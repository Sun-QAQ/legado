<template>
  <div class="book-grid" :class="{ 'book-list': list }">
    <article
      v-for="book in books"
      :key="`${book.origin}:${book.bookUrl}`"
      class="book-item"
    >
      <button
        class="book-open"
        @click="$emit('bookClick', book)"
        :aria-label="`${isSearch ? '加入书架并阅读' : '阅读'}《${book.name}》`"
      >
        <book-cover :book="book" />
        <div class="book-info">
          <h3 :title="book.name">{{ book.name }}</h3>
          <p class="book-author">{{ book.author || '佚名' }}</p>
          <p v-if="isSearch" class="book-source">{{ book.originName }}</p>
          <p v-if="isSearch && book.intro" class="book-intro">
            {{ book.intro }}
          </p>
          <template v-if="!isSearch"
            ><p class="book-chapter">
              已读：{{ (book as Book).durChapterTitle || '尚未开始' }}
            </p>
            <p class="book-chapter">
              最新：{{ book.latestChapterTitle || '暂无章节信息' }}
            </p>
            <p class="book-chapter">
              共 {{ (book as Book).totalChapterNum }} 章 ·
              {{ dateFormat((book as Book).lastCheckTime) }}
            </p></template
          >
          <p class="book-progress">
            <span>{{
              isSearch ? '加入并阅读 →' : `已读 ${bookProgress(book as Book)}%`
            }}</span
            ><span
              v-if="!isSearch && (book as Book).lastCheckCount > 0"
              class="update-tag"
              >有更新</span
            >
          </p>
          <el-progress
            v-if="!isSearch"
            :percentage="bookProgress(book as Book)"
            :stroke-width="4"
            :show-text="false"
          />
        </div>
      </button>
    </article>
  </div>
</template>
<script setup lang="ts">
import type { Book, SeachBook } from '@/book'
import { dateFormat } from '@/utils/utils'
import { bookProgress } from '@/utils/bookPresentation'
defineProps<{
  books: Array<Book | SeachBook>
  isSearch: boolean
  list?: boolean
}>()
defineEmits<{ bookClick: [book: Book | SeachBook] }>()
</script>
<style scoped>
.book-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 28px 24px;
}
.book-item {
  min-width: 0;
}
.book-open {
  background: transparent;
  border: 0;
  color: var(--web-text);
  padding: 0;
  text-align: left;
  width: 100%;
  cursor: pointer;
}
.book-open :deep(.book-cover) {
  max-width: 140px;
  margin-bottom: 16px;
  transition: transform 0.2s;
}
.book-open:hover :deep(.book-cover) {
  transform: translateY(-4px);
}
.book-info h3 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 15px;
  line-height: 1.6;
  min-height: 3.2em;
  margin: 0 0 4px;
  overflow-wrap: anywhere;
}
.book-info p {
  font-size: 12px;
  color: var(--web-muted);
  margin: 4px 0;
  overflow-wrap: anywhere;
}
.book-chapter {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.book-info .book-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  margin-top: 12px;
}
.update-tag {
  background: var(--web-tint);
  color: var(--web-primary);
  padding: 2px 5px;
  border-radius: 4px;
}
.book-intro {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.book-list {
  grid-template-columns: 1fr;
  gap: 0;
}
.book-list .book-item {
  padding: 18px 0;
  border-bottom: 1px solid var(--web-line);
}
.book-list .book-open {
  display: flex;
  align-items: center;
  gap: 22px;
}
.book-list .book-open :deep(.book-cover) {
  width: 74px;
  flex-shrink: 0;
  margin: 0;
}
.book-list .book-info {
  min-width: 0;
  flex: 1;
}
.book-list :deep(.el-progress) {
  max-width: 250px;
}
@media (max-width: 600px) {
  .book-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 24px 20px;
  }
  .book-open :deep(.book-cover) {
    max-width: none;
  }
  .book-list {
    grid-template-columns: 1fr;
  }
  .book-list .book-open :deep(.book-cover) {
    width: 65px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .book-open :deep(.book-cover) {
    transition: none;
  }
}
</style>

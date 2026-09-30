<template>
  <div class="web-shell" :class="{ 'reading-shell': reading }">
    <a class="skip-link" href="#web-main">跳到主要内容</a>
    <aside v-if="!reading" class="web-sidebar">
      <router-link to="/" class="web-brand"
        ><span class="brand-mark">阅</span
        ><span>阅读<small>LEGADO / WEB</small></span></router-link
      >
      <nav aria-label="主导航">
        <div class="nav-caption">我的阅读空间</div>
        <router-link to="/" :class="{ active: route.name === 'shelf' }"
          ><el-icon><Collection /></el-icon>我的书架<span class="nav-count">{{
            bookStore.shelf.length
          }}</span></router-link
        >
        <router-link to="/search" :class="{ active: route.name === 'search' }"
          ><el-icon><Search /></el-icon>在线搜索</router-link
        >
        <div class="nav-caption">内容与工具</div>
        <router-link
          to="/bookSource"
          :class="{ active: route.name === 'book-home' }"
          ><el-icon><Connection /></el-icon>书源管理</router-link
        >
        <router-link
          to="/rssSource"
          :class="{ active: route.name === 'rss-home' }"
          ><el-icon><Bell /></el-icon>订阅源管理</router-link
        >
        <router-link
          to="/settings"
          :class="{ active: route.name === 'settings' }"
          ><el-icon><Setting /></el-icon>连接与设置</router-link
        >
      </nav>
      <div class="sidebar-bottom">
        <p>清风不识字，何故乱翻书。</p>
        <router-link to="/settings" class="connection-card"
          ><span class="connection-dot" :class="connection.connectType" /><span
            >{{
              connection.connectType === 'danger'
                ? '连接异常'
                : connection.newConnect
                  ? '正在连接'
                  : '阅读 App 服务'
            }}<small>{{ connection.connectStatus }}</small></span
          ><span>›</span></router-link
        >
      </div>
    </aside>
    <div class="web-workspace">
      <header v-if="!reading" class="web-topbar">
        <span
          ><span class="muted">阅读空间</span
          ><span class="breadcrumb-separator">/</span>{{ title }}</span
        >
        <div class="inline-actions">
          <span class="desktop-label muted">让阅读回到内容本身</span
          ><el-button
            :icon="bookStore.isNight ? Sunny : Moon"
            circle
            aria-label="切换深浅主题"
            @click="toggleTheme"
          />
        </div>
      </header>
      <main id="web-main" class="web-main" :class="{ 'reader-main': reading }">
        <slot />
      </main>
      <footer v-if="!reading" class="web-footer">
        <span>LEGADO · 阅读</span><span>让阅读回到内容本身</span>
      </footer>
    </div>
  </div>
</template>
<script setup lang="ts">
import {
  Collection,
  Search,
  Connection,
  Bell,
  Setting,
  Moon,
  Sunny,
} from '@element-plus/icons-vue'
import API from '@api'
const route = useRoute()
const bookStore = useBookStore()
const connection = useConnectionStore()
const reading = computed(() => route.name === 'chapter')
const title = computed(
  () =>
    ({
      shelf: '我的书架',
      search: '在线搜索',
      'book-home': '书源管理',
      'rss-home': '订阅源管理',
      settings: '连接与设置',
    })[route.name as string] || '阅读',
)
const toggleTheme = async () => {
  bookStore.config.theme = bookStore.isNight ? 0 : 6
  try {
    await API.saveReadConfig(bookStore.config)
  } catch {
    /* 接口层已显示连接错误，保留本次界面选择。 */
  }
}
watch(
  () => bookStore.isNight,
  night => document.documentElement.classList.toggle('dark', night),
  { immediate: true },
)
onMounted(() => {
  bookStore.loadWebConfig().catch(() => {})
})
</script>

<template>
  <div
    class="web-shell"
    :class="{
      'reading-shell': reading,
      'sidebar-collapsed': sidebarCollapsed,
      'chat-shell': route.name === 'chat',
    }"
  >
    <a class="skip-link" href="#web-main">跳到主要内容</a>
    <aside v-if="!reading" class="web-sidebar">
      <el-button
        class="sidebar-toggle"
        :icon="sidebarCollapsed ? Expand : Fold"
        circle
        :aria-label="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        :title="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        :aria-expanded="!sidebarCollapsed"
        aria-controls="web-navigation"
        @click="sidebarCollapsed = !sidebarCollapsed"
      />
      <a href="../index.html" class="web-brand" aria-label="返回导航页">
        <img
          class="brand-mark"
          :src="brandIcon"
          alt="阅读 icon5"
          width="43"
          height="43"
        />
        <span>阅读<small>LEGADO / WEB</small></span>
      </a>
      <nav id="web-navigation" aria-label="主导航">
        <div class="nav-caption">我的阅读空间</div>
        <router-link
          to="/"
          :class="{ active: route.name === 'shelf' }"
          aria-label="我的书架"
          title="我的书架"
          ><el-icon><Collection /></el-icon
          ><span class="nav-label">我的书架</span
          ><span class="nav-count">{{
            bookStore.shelf.length
          }}</span></router-link
        >
        <router-link
          to="/search"
          :class="{ active: route.name === 'search' }"
          aria-label="在线搜索"
          title="在线搜索"
          ><el-icon><Search /></el-icon
          ><span class="nav-label">在线搜索</span></router-link
        >
        <router-link
          to="/explore"
          :class="{ active: route.name === 'explore' }"
          aria-label="书源发现"
          title="书源发现"
          ><el-icon><Compass /></el-icon
          ><span class="nav-label">书源发现</span></router-link
        >
        <div class="nav-caption">内容与工具</div>
        <router-link
          to="/upload"
          :class="{ active: route.name === 'upload' }"
          aria-label="传书"
          title="传书"
          ><el-icon><Upload /></el-icon
          ><span class="nav-label">传书</span></router-link
        >
        <router-link
          to="/bookSource"
          :class="{ active: route.name === 'book-home' }"
          aria-label="书源管理"
          title="书源管理"
          ><el-icon><Connection /></el-icon
          ><span class="nav-label">书源管理</span></router-link
        >
        <router-link
          to="/chat"
          :class="{ active: route.name === 'chat' }"
          aria-label="AI 对话"
          title="AI 对话"
          ><el-icon><ChatDotRound /></el-icon
          ><span class="nav-label">AI 对话</span></router-link
        >
        <router-link
          to="/rssSource"
          :class="{ active: route.name === 'rss-home' }"
          aria-label="订阅源管理"
          title="订阅源管理"
          ><el-icon><Bell /></el-icon
          ><span class="nav-label">订阅源管理</span></router-link
        >
        <router-link
          to="/settings"
          :class="{ active: route.name === 'settings' }"
          aria-label="连接与设置"
          title="连接与设置"
          ><el-icon><Setting /></el-icon
          ><span class="nav-label">连接与设置</span></router-link
        >
      </nav>
      <div class="sidebar-bottom">
        <p>清风不识字，何故乱翻书。</p>
        <router-link
          to="/settings"
          class="connection-card"
          :aria-label="connection.connectStatus"
          :title="connection.connectStatus"
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
      <main
        id="web-main"
        class="web-main"
        :class="{ 'reader-main': reading, 'chat-main': route.name === 'chat' }"
      >
        <slot />
      </main>
      <footer v-if="!reading && route.name !== 'chat'" class="web-footer">
        <span>LEGADO · 阅读</span><span>让阅读回到内容本身</span>
      </footer>
    </div>
  </div>
</template>
<script setup lang="ts">
import {
  ChatDotRound,
  Collection,
  Compass,
  Search,
  Connection,
  Upload,
  Bell,
  Setting,
  Moon,
  Sunny,
  Fold,
  Expand,
} from '@element-plus/icons-vue'
import API from '@api'
import { appAccentTokens } from '@/utils/appTheme'
const brandIcon = `${import.meta.env.BASE_URL}icon5.svg`
const route = useRoute()
const bookStore = useBookStore()
const connection = useConnectionStore()
const sidebarCollapsed = ref(true)
const reading = computed(() => route.name === 'chapter')
const title = computed(
  () =>
    ({
      shelf: '我的书架',
      'book-detail': '书籍详情',
      search: '在线搜索',
      explore: '书源发现',
      'book-home': '书源管理',
      'rss-home': '订阅源管理',
      settings: '连接与设置',
      upload: '传书',
      chat: 'AI 对话',
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
  () => [bookStore.appAccentColor, bookStore.isNight] as const,
  ([accent, night]) => {
    const root = document.documentElement
    root.classList.toggle('dark', night)
    // 移除旧色阶，让旧版 App 或无效颜色安全回退到默认主题。
    for (const key of Object.keys(appAccentTokens('#000000', night))) {
      root.style.removeProperty(key)
    }
    for (const [key, value] of Object.entries(appAccentTokens(accent, night))) {
      root.style.setProperty(key, value)
    }
  },
  { immediate: true },
)
onMounted(() => {
  bookStore.loadWebConfig().catch(() => {})
})
</script>

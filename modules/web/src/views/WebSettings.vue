<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">LEGADO READING SPACE</div>
      <h1>连接与设置</h1>
      <p>管理阅读 App 的连接，以及这个阅读空间的外观。</p>
    </div>
  </div>
  <div class="settings-grid">
    <section class="web-panel">
      <h2>连接阅读 App</h2>
      <p class="muted">在 App 中开启 Web 服务后，填写页面显示的地址。</p>
      <form @submit.prevent="connect">
        <label for="remote-address">服务地址</label
        ><el-input
          id="remote-address"
          v-model="address"
          placeholder="http://127.0.0.1:9527"
          :disabled="connecting"
          aria-label="服务地址"
        />
        <p class="connection-notice" role="status">
          {{ connection.connectStatus }}
        </p>
        <el-button type="primary" native-type="submit" :loading="connecting"
          >测试并连接</el-button
        >
      </form>
    </section>
    <section class="web-panel">
      <h2>外观与阅读</h2>
      <p class="muted">跟随你的习惯，保持简单。</p>
      <div class="setting-row">
        <span>界面主题</span
        ><el-switch v-model="night" active-text="夜间" inactive-text="日间" />
      </div>
      <div class="setting-row">
        <span>阅读排版</span
        ><span class="muted">字号 {{ store.config.fontSize }} px</span>
      </div>
      <p class="muted">
        阅读时可调整背景、字体、字号、间距和页面宽度，配置会同步至 App。
      </p>
      <el-button @click="$router.push('/')">回到书架</el-button>
    </section>
  </div>
</template>
<script setup lang="ts">
import API, {
  legado_http_entry_point,
  parseLeagdoHttpUrlWithDefault,
  setApiEntryPoint,
} from '@api'
import { baseURL_localStorage_key } from '@/api/axios'
import { validatorHttpUrl } from '@/utils/utils'
const store = useBookStore()
const connection = useConnectionStore()
const address = ref(legado_http_entry_point)
const connecting = ref(false)
const night = computed({
  get: () => store.isNight,
  set: value => {
    store.config.theme = value ? 6 : 0
    API.saveReadConfig(store.config).catch(() => {})
  },
})
const connect = async () => {
  if (!validatorHttpUrl(address.value.trim()))
    return ElMessage.error('请输入有效的 HTTP / HTTPS 地址')
  connecting.value = true
  connection.setNewConnect(true)
  try {
    const url = new URL(address.value.trim()).toString()
    const config = await API.getReadConfig(url)
    if (!config) throw new Error('服务未返回有效阅读配置')
    store.setConfig(config)
    setApiEntryPoint(...parseLeagdoHttpUrlWithDefault(url))
    if (url === new URL(location.origin).toString())
      localStorage.removeItem(baseURL_localStorage_key)
    else localStorage.setItem(baseURL_localStorage_key, url)
    store.clearSearchBooks()
    store.shelf = []
    store.catalog = []
    connection.setNewConnect(false)
    connection.setConnectStatus('已连接 ' + url)
    connection.setConnectType('success')
    ElMessage.success('连接成功')
  } catch (error) {
    connection.setNewConnect(false)
    connection.setConnectType('danger')
    connection.setConnectStatus('连接异常，请检查服务地址')
    ElMessage.error(error instanceof Error ? error.message : '连接失败')
  } finally {
    connecting.value = false
    connection.setNewConnect(false)
  }
}
</script>
<style scoped>
.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 22px;
}
.web-panel > p {
  margin: 10px 0 24px;
}
.web-panel label {
  display: block;
  margin: 20px 0 8px;
}
.connection-notice {
  padding: 14px;
  background: var(--web-tint);
  color: var(--web-primary);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.setting-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 16px 0;
  border-bottom: 1px solid var(--web-line);
}
@media (max-width: 900px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<template>
  <slot v-if="ready" />
  <main v-else class="pairing web-panel">
    <h1>连接阅读 App</h1>
    <p>如果 App 已开启网页服务鉴权，请在 App 的其他设置中复制网页访问密钥。</p>
    <form @submit.prevent="pair">
      <label for="pair-server">服务地址</label>
      <el-input id="pair-server" v-model="address" :disabled="busy" />
      <label for="pair-key">访问密钥</label>
      <el-input
        id="pair-key"
        v-model="key"
        type="password"
        show-password
        :disabled="busy"
        autocomplete="off"
      />
      <p v-if="error" role="alert">{{ error }}</p>
      <el-button type="primary" native-type="submit" :loading="busy"
        >连接并配对</el-button
      >
    </form>
  </main>
</template>
<script setup lang="ts">
import axios from 'axios'
import { getWebAccessKey, saveWebAccessKey } from '@/utils/webCredentials'
import ajax, { baseURL_localStorage_key } from '@/api/axios'
import { setApiEntryPoint, parseLeagdoHttpUrlWithDefault } from '@api'

const ready = ref(false)
const busy = ref(false)
const error = ref('')
const address = ref(String(ajax.defaults.baseURL || location.origin))
const key = ref('')
const lifetime = new AbortController()
const pair = async () => {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const [http, socket] = parseLeagdoHttpUrlWithDefault(address.value)
    const status = await axios
      .get(new URL('webAuth', http).toString(), {
        signal: lifetime.signal,
        timeout: 10000,
      })
      .catch(cause => {
        if (axios.isAxiosError(cause) && cause.response?.status === 404)
          return { data: { data: { enabled: false } } }
        throw cause
      })
    if (status.data?.data?.enabled) {
      const accessKey = key.value.trim() || getWebAccessKey(http)
      if (!accessKey) throw new Error('请输入 App 中的网页访问密钥')
      await axios.post(
        new URL('webAuth/pair', http).toString(),
        { key: accessKey },
        { signal: lifetime.signal, timeout: 10000 },
      )
      saveWebAccessKey(http, accessKey)
    }
    if (lifetime.signal.aborted) return
    setApiEntryPoint(http, socket)
    localStorage.setItem(baseURL_localStorage_key, http)
    key.value = ''
    ready.value = true
  } catch (cause) {
    if (!axios.isCancel(cause))
      error.value =
        axios.isAxiosError(cause) && cause.response?.status === 401
          ? '访问密钥无效，请从 App 重新复制'
          : cause instanceof Error
            ? cause.message
            : '连接失败，请检查服务地址'
  } finally {
    busy.value = false
  }
}
const requirePairing = () => {
  address.value = String(ajax.defaults.baseURL || location.origin)
  key.value = ''
  error.value = '访问授权已失效，请重新配对'
  ready.value = false
}
onMounted(() => {
  window.addEventListener('legado-web-unauthorized', requirePairing)
  pair()
})
onUnmounted(() => {
  lifetime.abort()
  window.removeEventListener('legado-web-unauthorized', requirePairing)
})
</script>
<style scoped>
.pairing {
  max-width: 560px;
  margin: 10vh auto;
  padding: 32px;
}
.pairing p {
  margin: 16px 0;
  line-height: 1.7;
}
.pairing label {
  display: block;
  margin: 20px 0 8px;
}
.pairing button {
  margin-top: 20px;
}
</style>

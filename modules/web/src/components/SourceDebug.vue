<template>
  <el-input
    v-if="isBookSource"
    id="debug-key"
    v-model="searchKey"
    placeholder="搜索书名、作者"
    :prefix-icon="Search"
    style="padding-bottom: 4px"
    @keydown.enter="startDebug"
  />
  <el-input
    id="debug-text"
    v-model="printDebug"
    type="textarea"
    readonly
    :rows="29"
    placeholder="这里用于输出调试信息"
  />
</template>

<script setup lang="ts">
import API from '@api'
import { Search } from '@element-plus/icons-vue'

const store = useSourceStore()

const printDebug = ref('')
const searchKey = ref('')
let socket: WebSocket | undefined
let disposed = false
onUnmounted(() => {
  disposed = true
  socket?.close()
  store.debugFinish()
})

watch(
  () => store.isDebuging,
  () => {
    if (store.isDebuging) startDebug()
  },
)

const appendDebugMsg = (msg: string) => {
  const debugDom = document.querySelector('#debug-text')
  debugDom!.scrollTop = debugDom!.scrollHeight
  printDebug.value += msg + '\n'
}
const startDebug = async () => {
  if (store.jsonError) {
    store.debugFinish()
    return ElMessage.error(store.jsonError)
  }
  const revision = JSON.stringify(store.currentSource)
  socket?.close()
  printDebug.value = ''
  try {
    const response = await API.saveSource(store.currentSource)
    if (!response.data.isSuccess)
      throw new Error(response.data.errorMsg || '保存源失败')
  } catch (e) {
    store.debugFinish()
    ElMessage.error(e instanceof Error ? e.message : '无法启动调试')
    return
  }
  if (disposed) return
  if (JSON.stringify(store.currentSource) === revision)
    store.saveCurrentSource()
  socket = API.debug(
    store.currentSourceUrl,
    searchKey.value || store.searchKey,
    appendDebugMsg,
    store.debugFinish,
  )
}

const isBookSource = computed(() => {
  return /bookSource/i.test(window.location.href)
})
</script>

<style lang="scss" scoped>
:deep(#debug-text) {
  min-height: 360px;
}
</style>

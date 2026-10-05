<template>
  <section class="ai-chat" aria-label="AI 对话" :aria-busy="loading">
    <header class="chat-toolbar">
      <el-select
        :model-value="selectedModelKey"
        filterable
        aria-label="AI 供应商与模型"
        :disabled="locked"
        placeholder="默认模型"
        @change="selectSupplier"
      >
        <el-option
          v-for="option in modelOptions"
          :key="option.value"
          :value="option.value"
          :label="option.label"
        />
      </el-select>
      <div class="chat-actions">
        <el-select
          :model-value="state.personaId"
          aria-label="AI 角色"
          :disabled="locked"
          @change="selectPersona"
        >
          <el-option value="0" label="默认角色" />
          <el-option
            v-for="persona in config.personas"
            :key="persona.id"
            :value="persona.id"
            :label="persona.name"
          />
        </el-select>
        <el-button
          :icon="Clock"
          aria-label="对话历史"
          title="对话历史"
          :disabled="loading || commandBusy"
          @click="openHistory"
        />
        <el-button
          :icon="Plus"
          aria-label="新对话"
          title="新对话"
          :disabled="locked"
          @click="newChat"
        />
      </div>
    </header>
    <div v-if="error" class="chat-error" role="alert">
      <span>{{ error }}</span
      ><el-button size="small" @click="initialize">重新连接</el-button>
    </div>
    <div
      class="conversation-stack"
      :class="{ 'is-empty': !state.messages.length }"
      ref="conversation"
      @scroll="onScroll"
    >
      <div v-if="!state.messages.length" class="chat-welcome">
        <h1>今天想聊点什么？</h1>
        <p>
          {{
            loading
              ? '正在读取 App 中的 AI 配置…'
              : !selectedModel
                ? '请先在 App 中配置并启用 AI 供应商和默认模型。'
                : '聊聊书里的世界，也聊聊你的想法。'
          }}
        </p>
      </div>
      <div
        v-else
        class="message-list"
        aria-live="polite"
        aria-relevant="additions text"
      >
        <article
          v-for="(message, index) in state.messages"
          :key="index"
          class="chat-message"
          :class="{ 'from-user': message.isUser }"
          :aria-label="message.isUser ? '我的消息' : 'AI 回复'"
        >
          <span v-if="!message.isUser" class="assistant-avatar"
            ><el-icon><ChatDotRound /></el-icon
          ></span>
          <div class="message-body">
            <div
              v-if="message.attachments?.length"
              class="attachment-list sent-attachments"
            >
              <div
                v-for="(file, fileIndex) in message.attachments"
                :key="fileIndex"
                class="attachment-card"
              >
                <img
                  v-if="file.imagePath"
                  :src="API.getProxyCoverUrl(file.imagePath)"
                  :alt="file.name"
                  loading="lazy"
                />
                <el-icon v-else><Document /></el-icon>
                <span :title="file.name"
                  >{{ file.name
                  }}<small>{{ formatSize(file.size) }}</small></span
                >
              </div>
            </div>
            <details
              v-if="message.steps.length"
              class="tool-steps"
              :open="message.streaming || state.waiting"
            >
              <summary>处理过程 · {{ message.steps.length }} 步</summary>
              <p
                v-for="step in message.steps"
                :key="step.id"
                :class="{ 'step-failed': step.state === 'FAILED' }"
              >
                <span>{{
                  step.state === 'RUNNING'
                    ? '◌'
                    : step.state === 'FAILED'
                      ? '!'
                      : '✓'
                }}</span
                >{{ step.title
                }}<small v-if="step.summary">{{ step.summary }}</small>
              </p>
            </details>
            <template
              v-for="(part, partIndex) in messageParts(message.text)"
              :key="partIndex"
            >
              <pre
                v-if="part.code"
                class="message-code"
              ><code>{{ part.text }}</code></pre>
              <div v-else class="message-text">{{ part.text }}</div>
            </template>
            <span v-if="message.streaming && !message.text" class="reply-status"
              >正在思考…</span
            >
            <span
              v-if="message.streaming"
              class="streaming-dot"
              aria-label="正在生成"
            />
            <ul v-if="message.books.length" class="chat-books">
              <li v-for="(book, bookIndex) in message.books" :key="bookIndex">
                <strong>{{ book.name }}</strong
                ><span>{{ book.author }} · {{ book.originName }}</span>
              </li>
            </ul>
            <img
              v-for="(image, imageIndex) in message.images"
              :key="imageIndex"
              class="generated-image"
              :src="API.getProxyCoverUrl(image.path)"
              :alt="image.prompt"
              loading="lazy"
            />
            <el-button
              v-if="message.canLoadMore"
              size="small"
              :disabled="locked"
              @click="runAction('more')"
              >继续搜索</el-button
            >
          </div>
        </article>
      </div>
      <form
        class="chat-composer"
        :class="{ 'composer-bottom': state.messages.length }"
        @submit.prevent="send"
        @dragover.prevent
        @drop.prevent="onDrop"
      >
        <input
          ref="fileInput"
          class="attachment-input"
          type="file"
          multiple
          :accept="attachmentAccept"
          aria-label="选择图片或文本文件"
          @change="onFilesSelected"
        />
        <div
          v-if="attachments.length"
          class="attachment-list"
          aria-label="待发送附件"
        >
          <div
            v-for="file in attachments"
            :key="file.id"
            class="attachment-card"
          >
            <img v-if="file.image" :src="file.dataUrl" :alt="file.name" />
            <el-icon v-else><Document /></el-icon>
            <span :title="file.name"
              >{{ file.name }}<small>{{ formatSize(file.size) }}</small></span
            >
            <el-button
              :icon="Close"
              circle
              size="small"
              :aria-label="`移除附件 ${file.name}`"
              :disabled="commandBusy || attachmentBusy"
              @click="removeAttachment(file.id)"
            />
          </div>
        </div>
        <textarea
          v-model="draft"
          ref="input"
          aria-label="消息内容"
          placeholder="想问什么都可以"
          rows="3"
          maxlength="32000"
          :disabled="loading || commandBusy || attachmentBusy"
          @keydown="onInputKeydown"
          @paste="onPaste"
        />
        <div class="composer-actions">
          <el-button
            :icon="Paperclip"
            circle
            aria-label="添加图片或文件"
            title="添加图片或文本文件（最多4个，图片5MB，文本256KB）"
            :disabled="locked || attachmentBusy || attachments.length >= 4"
            @click="fileInput?.click()"
          />
          <span>{{
            attachmentBusy
              ? '正在读取附件…'
              : state.waiting
                ? '正在生成回复'
                : 'Enter 发送 · Shift + Enter 换行'
          }}</span>
          <el-button
            v-if="state.waiting"
            :icon="Close"
            circle
            aria-label="停止生成"
            title="停止生成"
            :disabled="commandBusy"
            @click="runAction('cancel')"
          />
          <el-button
            v-else
            :icon="Top"
            type="primary"
            circle
            native-type="submit"
            aria-label="发送消息"
            title="发送消息"
            :disabled="
              loading ||
              commandBusy ||
              attachmentBusy ||
              !selectedModel ||
              (!draft.trim() && !attachments.length) ||
              !!error
            "
          />
        </div>
      </form>
    </div>
    <el-drawer
      v-model="historyVisible"
      title="对话历史"
      :size="'min(420px, 92vw)'"
    >
      <p v-if="historyLoading" class="muted">正在加载…</p>
      <p v-else-if="!history.length" class="muted">还没有对话记录</p>
      <button
        v-for="item in history"
        :key="item.id"
        class="history-item"
        :class="{ selected: item.id === state.conversationId }"
        :disabled="commandBusy"
        @click="selectHistory(item.id)"
      >
        <strong>{{ item.title }}</strong
        ><small>{{ new Date(item.updatedAt).toLocaleString() }}</small>
      </button>
    </el-drawer>
  </section>
</template>
<script setup lang="ts">
import {
  ChatDotRound,
  Clock,
  Close,
  Plus,
  Top,
  Paperclip,
  Document,
} from '@element-plus/icons-vue'
import API from '@api'
import {
  chatAction,
  getChatConfig,
  getChatHistory,
  getChatState,
  type ChatConfig,
  type ChatHistory,
  type ChatState,
} from '@/api/aiChat'
import { isCancel } from 'axios'

const state = ref<ChatState>({
  conversationId: null,
  waiting: false,
  supplierId: '0',
  personaId: '0',
  messages: [],
})
const config = ref<ChatConfig>({
  suppliers: [],
  personas: [],
  supplierId: '0',
  personaId: '0',
})
const draft = ref('')
const loading = ref(true)
const commandBusy = ref(false)
const error = ref('')
const history = ref<ChatHistory[]>([])
const historyVisible = ref(false)
const historyLoading = ref(false)
const conversation = ref<HTMLDivElement>()
const input = ref<HTMLTextAreaElement>()
const fileInput = ref<HTMLInputElement>()
interface DraftAttachment {
  id: number
  name: string
  size: number
  image: boolean
  dataUrl: string
}
const attachments = ref<DraftAttachment[]>([])
const attachmentBusy = ref(false)
let attachmentId = 0
const textExtensions = [
  'txt',
  'md',
  'csv',
  'json',
  'log',
  'xml',
  'html',
  'htm',
  'yaml',
  'yml',
  'js',
  'ts',
  'py',
  'kt',
  'java',
  'css',
  'sql',
]
const imageExtensions = ['png', 'jpg', 'jpeg', 'gif', 'webp']
const attachmentAccept = [...imageExtensions, ...textExtensions]
  .map(ext => '.' + ext)
  .join(',')
const formatSize = (size: number) =>
  size >= 1024 * 1024
    ? `${(size / (1024 * 1024)).toFixed(1)} MB`
    : `${Math.max(1, Math.ceil(size / 1024))} KB`
const readFile = (file: File): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error(`无法读取 ${file.name}`))
    reader.readAsDataURL(file)
  })
const addFiles = async (files: File[]) => {
  if (locked.value || attachmentBusy.value) return
  attachmentBusy.value = true
  try {
    for (const file of files) {
      const ext = file.name.split('.').pop()?.toLowerCase() || ''
      const image = imageExtensions.includes(ext)
      if (!image && !textExtensions.includes(ext)) {
        ElMessage.warning(
          '支持 PNG、JPEG、GIF、WebP 图片及 TXT、Markdown、CSV、JSON 等文本文件',
        )
        continue
      }
      if (!file.size || file.size > (image ? 5 * 1024 * 1024 : 256 * 1024)) {
        ElMessage.warning(
          `${file.name}：${image ? '图片不能超过 5 MB' : '文本文件不能超过 256 KB'}，且文件不能为空`,
        )
        continue
      }
      if (
        attachments.value.length >= 4 ||
        attachments.value.reduce((sum, item) => sum + item.size, 0) +
          file.size >
          10 * 1024 * 1024
      ) {
        ElMessage.warning('最多 4 个附件，总大小不能超过 10 MB')
        break
      }
      const dataUrl = await readFile(file)
      if (lifetime.signal.aborted) return
      attachments.value.push({
        id: ++attachmentId,
        name: file.name,
        size: file.size,
        image,
        dataUrl,
      })
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '附件读取失败')
  } finally {
    attachmentBusy.value = false
  }
}
const onFilesSelected = (event: Event) => {
  const el = event.target as HTMLInputElement
  const files = Array.from(el.files || [])
  el.value = ''
  addFiles(files)
}
const onDrop = (event: DragEvent) =>
  addFiles(Array.from(event.dataTransfer?.files || []))
const onPaste = (event: ClipboardEvent) => {
  const files = Array.from(event.clipboardData?.files || [])
  if (files.length) {
    event.preventDefault()
    addFiles(files)
  }
}
const removeAttachment = (id: number) => {
  attachments.value = attachments.value.filter(file => file.id !== id)
}
const locked = computed(
  () =>
    loading.value ||
    commandBusy.value ||
    attachmentBusy.value ||
    state.value.waiting,
)
const selectedModel = computed(
  () =>
    state.value.model ||
    config.value.suppliers.find(item => item.id === state.value.supplierId)
      ?.model,
)
const selectedModelKey = computed(() =>
  state.value.supplierId === '0' || !selectedModel.value
    ? undefined
    : JSON.stringify([state.value.supplierId, selectedModel.value]),
)
const modelOptions = computed(() =>
  config.value.suppliers.flatMap(source =>
    [...new Set([source.model, ...(source.models || [])])]
      .filter(Boolean)
      .map(model => ({
        value: JSON.stringify([source.id, model]),
        label: `${source.name} · ${model}`,
      })),
  ),
)
const lifetime = new AbortController()
let polling: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let revision = 0
let followReply = true

const messageParts = (text: string) =>
  text
    .split(/```(?:[^\n]*\n)?([\s\S]*?)(?:```|$)/g)
    .map((text, index) => ({ code: index % 2 === 1, text }))
const onScroll = () => {
  const el = conversation.value
  if (el) followReply = el.scrollHeight - el.scrollTop - el.clientHeight < 100
}
const applyState = async (value: ChatState) => {
  state.value = value
  await nextTick()
  if (followReply && conversation.value)
    conversation.value.scrollTop = conversation.value.scrollHeight
}
const stopPolling = () => {
  clearTimeout(timer)
  polling?.abort()
  polling = undefined
  revision++
}
const schedulePoll = () => {
  clearTimeout(timer)
  if (!state.value.waiting || lifetime.signal.aborted || error.value) return
  const currentRevision = revision
  timer = setTimeout(async () => {
    polling = new AbortController()
    try {
      const value = await getChatState(polling.signal)
      if (revision !== currentRevision || lifetime.signal.aborted) return
      await applyState(value)
      schedulePoll()
    } catch (e) {
      if (
        !isCancel(e) &&
        !lifetime.signal.aborted &&
        revision === currentRevision
      )
        error.value = '对话连接中断，请重新连接以继续查看回复。'
    }
  }, 600)
}
const initialize = async () => {
  stopPolling()
  loading.value = true
  error.value = ''
  try {
    config.value = await getChatConfig(lifetime.signal)
    const failed = config.value.suppliers.filter(source => source.modelError)
    if (failed.length)
      ElMessage.warning(
        failed.map(source => `${source.name}：${source.modelError}`).join('；'),
      )
    await applyState(await getChatState(lifetime.signal))
    schedulePoll()
  } catch (e) {
    if (!isCancel(e))
      error.value = e instanceof Error ? e.message : '读取 AI 配置失败'
  } finally {
    loading.value = false
  }
}
const runAction = async (
  action: string,
  data: Record<string, unknown> = {},
) => {
  if (commandBusy.value) return false
  stopPolling()
  commandBusy.value = true
  try {
    await applyState(await chatAction(action, data, lifetime.signal))
    error.value = ''
    return true
  } catch (e) {
    if (!isCancel(e))
      ElMessage.error(e instanceof Error ? e.message : '对话操作失败')
    return false
  } finally {
    commandBusy.value = false
    schedulePoll()
  }
}
const send = async () => {
  if (
    locked.value ||
    attachmentBusy.value ||
    !selectedModel.value ||
    error.value ||
    (!draft.value.trim() && !attachments.value.length)
  )
    return
  const text = draft.value.trim()
  const files = attachments.value.slice()
  draft.value = ''
  followReply = true
  if (
    await runAction('send', {
      text,
      attachments: files.map(({ name, dataUrl }) => ({ name, dataUrl })),
    })
  )
    attachments.value = []
  else draft.value = text
  await nextTick()
  input.value?.focus()
}
const onInputKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}
const selectSupplier = (value: string) => {
  const [id, model] = JSON.parse(value) as [string, string]
  return runAction('supplier', { id, model })
}
const selectPersona = (id: string) => runAction('persona', { id })
const newChat = async () => {
  if (await runAction('new')) {
    draft.value = ''
    attachments.value = []
    followReply = true
    input.value?.focus()
  }
}
const openHistory = async () => {
  historyVisible.value = true
  historyLoading.value = true
  try {
    history.value = await getChatHistory(lifetime.signal)
  } catch (e) {
    if (!isCancel(e))
      ElMessage.error(e instanceof Error ? e.message : '读取历史失败')
  } finally {
    historyLoading.value = false
  }
}
const selectHistory = async (id: string) => {
  followReply = true
  if (await runAction('open', { id })) {
    historyVisible.value = false
    draft.value = ''
    attachments.value = []
  }
}
onMounted(initialize)
onBeforeUnmount(() => {
  lifetime.abort()
  stopPolling()
})
</script>
<style scoped>
.ai-chat {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 24px;
}
.chat-toolbar > .el-select {
  width: min(380px, 45%);
}
.chat-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chat-actions .el-select {
  width: 140px;
}
.chat-actions .el-button {
  margin: 0;
}
.conversation-stack {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: 16px 24px 20px;
}
.conversation-stack.is-empty {
  justify-content: center;
  padding-bottom: clamp(24px, 12vh, 120px);
}
.chat-welcome {
  text-align: center;
  margin-bottom: 24px;
}
.chat-welcome h1 {
  font-size: clamp(24px, 3vw, 32px);
  margin: 0 0 12px;
}
.chat-welcome p {
  font-size: 13px;
  color: var(--web-muted);
}
.chat-composer {
  width: min(100%, 800px);
  align-self: center;
  flex-shrink: 0;
  border: 1px solid var(--web-line);
  background: var(--web-surface);
  border-radius: 24px;
  padding: 16px;
  box-shadow: 0 2px 8px #00000008;
  box-sizing: border-box;
}
.chat-composer:focus-within {
  border-color: var(--el-color-primary);
}
.chat-composer textarea {
  box-sizing: border-box;
  display: block;
  width: 100%;
  border: 0;
  outline: none;
  resize: vertical;
  min-height: 64px;
  max-height: 200px;
  font: inherit;
  font-size: 14px;
  line-height: 1.6;
  background: transparent;
  color: var(--web-text);
}
.chat-composer textarea::placeholder {
  color: var(--web-muted);
}
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.composer-actions > span {
  flex: 1;
  font-size: 11px;
  color: var(--web-muted);
}
.attachment-input {
  display: none;
}
.attachment-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-height: 180px;
  overflow-y: auto;
  margin-bottom: 12px;
}
.attachment-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  min-width: 0;
  max-width: 100%;
  border: 1px solid var(--web-line);
  background: var(--web-surface);
  border-radius: 12px;
  box-sizing: border-box;
}
.attachment-card img {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 8px;
}
.attachment-card > .el-icon {
  font-size: 24px;
  color: var(--web-primary);
}
.attachment-card > span {
  min-width: 0;
  max-width: 160px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 12px;
}
.attachment-card small {
  display: block;
  color: var(--web-muted);
  font-size: 10px;
}
.attachment-card .el-button {
  flex-shrink: 0;
  margin: 0;
}
.sent-attachments {
  max-height: none;
}
.composer-bottom {
  position: sticky;
  bottom: 0;
  margin-top: auto;
}
.message-list {
  width: min(100%, 800px);
  margin: 0 auto;
  flex: 1;
  padding-bottom: 24px;
}
.chat-message {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 24px;
}
.assistant-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  color: var(--el-color-primary);
  background: var(--web-tint);
  flex-shrink: 0;
}
.message-body {
  min-width: 0;
  max-width: 100%;
  flex: 1;
  font-size: 15px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.message-text {
  white-space: pre-wrap;
}
.from-user {
  justify-content: flex-end;
}
.from-user .message-body {
  flex: 0 1 auto;
  padding: 10px 18px;
  max-width: 85%;
  border-radius: 20px 20px 4px 20px;
  background: var(--web-tint);
}
.message-code {
  overflow-x: auto;
  padding: 16px;
  border: 1px solid var(--web-line);
  border-radius: 12px;
  background: var(--web-surface);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
}
.streaming-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-left: 4px;
  border-radius: 50%;
  background: var(--el-color-primary);
}
.reply-status,
.tool-steps {
  color: var(--web-muted);
  font-size: 12px;
}
.tool-steps summary {
  cursor: pointer;
}
.tool-steps p {
  margin: 6px 0;
}
.tool-steps p > span {
  margin-right: 8px;
}
.tool-steps small {
  display: block;
  padding-left: 18px;
}
.step-failed {
  color: var(--el-color-danger);
}
.chat-books {
  list-style: none;
  padding: 0;
}
.chat-books li {
  border: 1px solid var(--web-line);
  border-radius: 12px;
  padding: 10px 16px;
  margin: 8px 0;
}
.chat-books span {
  display: block;
  font-size: 12px;
  color: var(--web-muted);
}
.generated-image {
  display: block;
  max-width: 100%;
  max-height: 520px;
  border-radius: 12px;
  margin: 12px 0;
}
.chat-error {
  margin: 0 24px;
  padding: 10px 16px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 13px;
}
.history-item {
  width: 100%;
  display: block;
  text-align: left;
  border: 1px solid var(--web-line);
  background: var(--web-surface);
  border-radius: 12px;
  padding: 14px;
  margin-bottom: 10px;
  color: var(--web-text);
  cursor: pointer;
}
.history-item.selected {
  border-color: var(--el-color-primary);
}
.history-item strong {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.history-item small {
  display: block;
  margin-top: 6px;
  color: var(--web-muted);
}
@media (max-width: 600px) {
  .chat-toolbar {
    padding: 12px;
    gap: 8px;
    flex-wrap: wrap;
  }
  .chat-toolbar > .el-select {
    width: calc(100% - 90px);
    flex: 1;
  }
  .chat-actions {
    gap: 4px;
  }
  .chat-actions .el-select {
    width: 100px;
  }
  .conversation-stack {
    padding: 12px;
  }
  .chat-error {
    margin: 0 12px;
  }
  .message-body {
    font-size: 14px;
  }
  .assistant-avatar {
    width: 26px;
    height: 26px;
  }
  .chat-message {
    gap: 8px;
  }
}
</style>

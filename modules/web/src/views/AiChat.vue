<template>
  <section class="ai-chat" aria-label="AI 对话" :aria-busy="loading">
    <header class="chat-toolbar">
      <el-select
        :model-value="state.supplierId === '0' ? undefined : state.supplierId"
        aria-label="AI 供应商与模型"
        :disabled="locked"
        placeholder="默认模型"
        @change="selectSupplier"
      >
        <el-option
          v-for="source in config.suppliers"
          :key="source.id"
          :value="source.id"
          :label="
            source.model
              ? `${source.name} · ${source.model}`
              : `${source.name} · 未配置模型`
          "
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
      >
        <textarea
          v-model="draft"
          ref="input"
          aria-label="消息内容"
          placeholder="想问什么都可以"
          rows="3"
          maxlength="32000"
          :disabled="loading || commandBusy"
          @keydown="onInputKeydown"
        />
        <div class="composer-actions">
          <span>{{
            state.waiting ? '正在生成回复' : 'Enter 发送 · Shift + Enter 换行'
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
              !selectedModel ||
              !draft.trim() ||
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
import { ChatDotRound, Clock, Close, Plus, Top } from '@element-plus/icons-vue'
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
const locked = computed(
  () => loading.value || commandBusy.value || state.value.waiting,
)
const selectedModel = computed(
  () =>
    config.value.suppliers.find(item => item.id === state.value.supplierId)
      ?.model,
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
    await applyState(await getChatState(lifetime.signal))
    schedulePoll()
  } catch (e) {
    if (!isCancel(e))
      error.value = e instanceof Error ? e.message : '读取 AI 配置失败'
  } finally {
    loading.value = false
  }
}
const runAction = async (action: string, data: Record<string, string> = {}) => {
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
    !selectedModel.value ||
    error.value ||
    !draft.value.trim()
  )
    return
  const text = draft.value.trim()
  draft.value = ''
  followReply = true
  if (!(await runAction('send', { text }))) draft.value = text
  await nextTick()
  input.value?.focus()
}
const onInputKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    send()
  }
}
const selectSupplier = (id: string) => runAction('supplier', { id })
const selectPersona = (id: string) => runAction('persona', { id })
const newChat = async () => {
  if (await runAction('new')) {
    draft.value = ''
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
  if (await runAction('open', { id })) historyVisible.value = false
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
  font-size: 11px;
  color: var(--web-muted);
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

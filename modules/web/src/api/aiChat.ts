import ajax from './axios'
import type { LeagdoApiResponse } from './api'

export interface ChatMessage {
  isUser: boolean
  text: string
  streaming: boolean
  canLoadMore: boolean
  steps: { id: string; title: string; state: string; summary?: string }[]
  books: { name: string; author: string; originName: string }[]
  images: { path: string; prompt: string }[]
}
export interface ChatState {
  conversationId: string | null
  waiting: boolean
  supplierId: string
  personaId: string
  messages: ChatMessage[]
}
export interface ChatConfig {
  suppliers: { id: string; name: string; model: string }[]
  personas: { id: string; name: string }[]
  supplierId: string
  personaId: string
}
export interface ChatHistory {
  id: string
  title: string
  updatedAt: number
}
const unwrap = <T>(result: LeagdoApiResponse<T>): T => {
  if (!result.isSuccess) throw new Error(result.errorMsg || 'AI 对话操作失败')
  return result.data
}
export const getChatConfig = async (signal: AbortSignal) =>
  unwrap(
    (
      await ajax.get<LeagdoApiResponse<ChatConfig>>('getAiChatConfig', {
        signal,
      })
    ).data,
  )
export const getChatState = async (signal: AbortSignal) =>
  unwrap(
    (await ajax.get<LeagdoApiResponse<ChatState>>('getAiChat', { signal }))
      .data,
  )
export const getChatHistory = async (signal: AbortSignal) =>
  unwrap(
    (
      await ajax.get<LeagdoApiResponse<ChatHistory[]>>('getAiChatHistory', {
        signal,
      })
    ).data,
  )
export const chatAction = async (
  action: string,
  data: Record<string, string>,
  signal: AbortSignal,
) =>
  unwrap(
    (
      await ajax.post<LeagdoApiResponse<ChatState>>(
        'aiChat',
        { action, ...data },
        { signal },
      )
    ).data,
  )

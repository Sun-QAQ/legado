import type { ChatState } from '../api/aiChat'

/** A mismatched delta requests a full snapshot instead of modifying the visible conversation. */
export const mergeChatState = (
  current: ChatState,
  incoming: ChatState,
): ChatState | null => {
  if (incoming.reset !== false) return incoming
  if (
    !current.revision ||
    incoming.baseRevision !== current.revision ||
    incoming.conversationId !== current.conversationId
  )
    return null
  const messages = new Map(
    current.messages.map(message => [message.id, message]),
  )
  if (messages.has(undefined) || incoming.messages.some(message => !message.id))
    return null
  for (const message of incoming.messages) messages.set(message.id, message)
  return { ...incoming, messages: [...messages.values()] }
}

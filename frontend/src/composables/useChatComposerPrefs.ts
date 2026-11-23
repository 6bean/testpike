/**
 * Composer presentation preference shared by both AI chat surfaces (the global
 * dock and the standalone page run one kernel, so they must read one state —
 * never a second copy of the same decision).
 *
 * Personal preference only: it rides localStorage in a per-user bucket, the
 * same posture as the dock width and the floating-button placement. It is never
 * a server-side config key.
 *
 * The flag gates ONLY the send door. Run-control doors (stop / keep receiving)
 * are unaffected: hiding the send button must never leave a streaming answer
 * without an exit the user can see.
 */
import { ref } from 'vue'

const KEY_PREFIX = 'tp_chat_send_button'

/** Shared singleton state: the surfaces that mount later see the current value. */
const showSendButton = ref(false)

/** Bound bucket of the live identity; null while no user is known yet. */
let boundBucket: string | null = null

export function sendButtonBucket(userId: string | number | null | undefined): string | null {
  if (userId === null || userId === undefined) return null
  return `${KEY_PREFIX}:${userId}`
}

function parseFlag(raw: string | null): boolean {
  return raw === '1' || raw === 'true'
}

/**
 * Point the shared state at one identity bucket and load its value. An unknown
 * identity keeps the factory default (button hidden, Enter sends) and writes
 * nothing, so a later login never resurrects an anonymous experiment.
 */
export function bindChatComposerPrefs(bucket: string | null): void {
  if (bucket === boundBucket) return
  boundBucket = bucket
  showSendButton.value = bucket === null ? false : parseFlag(localStorage.getItem(bucket))
}

/** Persist a new value into the live bucket (no-op before an identity is known). */
export function setShowSendButton(visible: boolean): void {
  showSendButton.value = visible
  if (boundBucket !== null) localStorage.setItem(boundBucket, visible ? '1' : '0')
}

export function useChatComposerPrefs() {
  return { showSendButton, setShowSendButton, bindChatComposerPrefs }
}

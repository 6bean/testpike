/**
 * Failure classification and the resend door for the chat dock.
 *
 * A failed answer line must name WHICH kind of failure happened — one of
 * the four report classes (network break / stream break / timeout / empty
 * completion) — and offer a one-click resend of the original question.
 *
 * Kinds come from two places: the stream client (aiChat emits network /
 * interrupted / no-body / timeout / refused) and the dock itself (an empty
 * completion is detected when a done frame seals a blank answer — nothing on
 * the wire says "empty", the seal shape does). failureClassOf is the single
 * fold point: the UI never branches on raw kinds, so a future wire kind
 * cannot silently mislabel an existing line.
 */

/** Display classes of a failed reply (four report classes + server refusal). */
export type ChatFailureClass = 'network' | 'interrupted' | 'timeout' | 'empty' | 'server'

/**
 * How long a live stream may stay silent before the dock calls it a timeout.
 * One exported source (no per-caller magic numbers): deliberately generous —
 * the dwell bands of the awaiting segment already narrate 15s/60s waits, so
 * failing here means "nothing EVER arrived", not "the model is slow".
 */
export const CHAT_FIRST_FRAME_TIMEOUT_MS = 300_000

/** Fold a wire/dock kind into its display class (unknown kinds give null). */
export function failureClassOf(kind: string | null | undefined): ChatFailureClass | null {
  switch (kind) {
    case 'network':
      return 'network'
    case 'interrupted':
    case 'no-body':
      return 'interrupted'
    case 'timeout':
      return 'timeout'
    case 'empty':
      return 'empty'
    case 'refused':
      return 'server'
    default:
      return null
  }
}

/** True while an answer holds no characters (the empty-completion detector). */
export function isBlankAnswer(text: string | null | undefined): boolean {
  return (text ?? '').trim() === ''
}

/**
 * A line can be resent only when it failed AND its request was captured —
 * sealed/running lines and turns older than this feature have nothing to
 * replay. A resend is a NEW send through the regular door: the ledger keeps
 * the failed turn and the new turn as two separate records, so an identical
 * replay never double-books anything.
 */
export function canResend(
  turn: { state?: string; resendPayload?: unknown } | null | undefined,
): boolean {
  return !!turn && turn.state === 'failed' && turn.resendPayload != null
}

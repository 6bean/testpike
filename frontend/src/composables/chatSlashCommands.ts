/**
 * Slash-command word table for the dialog input .
 *
 * The code ships STABLE KEYS only — every display string resolves through the
 * locale entries `layout.chatDock.slash.*` on both tracks (i18n single source;
 * the zero-CJK literal rule of the TC-AO word-table adjudication). Selecting
 * a command drops its locale text into the composer as the question template;
 * no hidden second copy lives in components.
 */

/** Command keys; each MUST have a locale entry on zh-CN and en-US (contract-tested). */
export const SLASH_COMMAND_KEYS = [
  'analyzeFailures',
  'caseCoverage',
  'projectStatus',
  'explainPage',
] as const

export type SlashCommandKey = (typeof SLASH_COMMAND_KEYS)[number]

/** The list the popup renders (a fresh array — callers never mutate the table). */
export function slashCommandKeys(): SlashCommandKey[] {
  return [...SLASH_COMMAND_KEYS]
}

/** Locale key of one command's display text (label doubles as the sent template). */
export function slashLabelKey(key: SlashCommandKey): string {
  return `layout.chatDock.slash.${key}`
}

export type TriggerKind = 'slash' | 'mention'

export interface TriggerState {
  kind: TriggerKind | null
  /** Text typed after the marker (drives the candidate filter). */
  token: string
  /** Index of the marker character in the value (-1 when no trigger). */
  markerAt: number
}

/**
 * Which popup (if any) the caret currently sits inside.
 * A marker fires only at the start of a line or right after whitespace, and
 * the token may not contain another marker — emails (`a@b.com`) and paths
 * (`x/y`) never hijack the input (TC-AO word-table honesty spectrum).
 */
export function detectTrigger(value: string, caret: number): TriggerState {
  const before = value.slice(0, Math.max(0, caret))
  const match = /(?:^|\s)([/@])([^/@\n]*)$/.exec(before)
  if (!match) return { kind: null, token: '', markerAt: -1 }
  return {
    kind: match[1] === '/' ? 'slash' : 'mention',
    token: match[2],
    markerAt: match.index + (before[match.index] === '/' || before[match.index] === '@' ? 0 : 1),
  }
}

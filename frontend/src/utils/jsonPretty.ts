/**
 * Pretty-print stored JSON text for read-only detail surfaces (single source).
 *
 * Invalid or empty payloads render as-is instead of being silently swallowed
 * (silent-failure defense): a broken stored value must stay visible to the
 * reader, and null/undefined shows the em-dash placeholder.
 */
export function prettyJson(text: string | null | undefined): string {
  if (!text) return '—'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

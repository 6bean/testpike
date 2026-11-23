


export interface JsonTextCheck {
  ok: boolean
  value: string | null
}


export function checkJsonText(text: string): JsonTextCheck {
  const trimmed = text.trim()
  if (!trimmed) return { ok: true, value: null }
  try {
    JSON.parse(trimmed)
    return { ok: true, value: trimmed }
  } catch {
    return { ok: false, value: null }
  }
}


export function checkJsonObject(text: string): JsonTextCheck {
  const r = checkJsonText(text)
  if (!r.ok || r.value == null) return r
  let parsed: unknown
  try {
    parsed = JSON.parse(r.value)
  } catch {
    return { ok: false, value: null }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { ok: false, value: null }
  }
  return r
}

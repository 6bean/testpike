

export interface AuditDiffRow {
  field: string
  before: unknown
  after: unknown
  changed: boolean
}


export function hasNoSnapshot(beforeJson: string | null, afterJson: string | null): boolean {
  return unwrapValueWrapper(parseJson(beforeJson)) === null
    && unwrapValueWrapper(parseJson(afterJson)) === null
}


export function buildAuditDiffRows(
  beforeJson: string | null,
  afterJson: string | null,
  snapshotField: string,
): AuditDiffRow[] {
  const before = unwrapValueWrapper(parseJson(beforeJson))
  const after = unwrapValueWrapper(parseJson(afterJson))
  if (before === null && after === null) return []
  if (!isPlainObject(before) || !isPlainObject(after)) {
    const b = formatSide(before)
    const a = formatSide(after)
    return [{ field: snapshotField, before: b, after: a, changed: JSON.stringify(b) !== JSON.stringify(a) }]
  }
  const keys = new Set<string>([...Object.keys(before), ...Object.keys(after)])
  const rows: AuditDiffRow[] = []
  for (const key of keys) {
    const b = before[key]
    const a = after[key]
    rows.push({ field: key, before: b ?? null, after: a ?? null, changed: JSON.stringify(b) !== JSON.stringify(a) })
  }
  return rows
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v)
}

function formatSide(v: unknown): unknown {
  if (v === null || v === undefined) return null
  return typeof v === 'object' ? JSON.stringify(v) : v
}


function unwrapValueWrapper(obj: unknown): unknown {
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    const keys = Object.keys(obj as Record<string, unknown>)
    if (keys.length === 1 && keys[0] === 'value') {
      return (obj as Record<string, unknown>).value
    }
  }
  return obj
}

function parseJson(s: string | null): unknown {
  if (!s) return null
  try {
    return JSON.parse(s)
  } catch {
    return s
  }
}

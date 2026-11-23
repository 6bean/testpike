import type { ApiEndpointView, ApiSpecView } from '@/api/apiSpec'

/**
 * Version-compare model for the API inventory page (contract snapshot pair →
 * added/changed/removed rows plus field-level schema pairs for the side-by-side
 * drawer). This is a display helper over two ApiSpecView endpoint snapshots —
 * the authoritative diff ledger stays core-side (diffSummary on adjacent
 * versions); nothing here re-judges contracts for the rest of the system.
 */

/** One field row parsed out of a JSON-schema-ish body (name/type/required). */
export interface SchemaFieldRow {
  name: string
  type: string
  required: boolean
}

/** Result of parsing one schema string: field rows, or honest raw fallback. */
export type SchemaParseResult =
  | { ok: true; fields: SchemaFieldRow[] }
  | { ok: false; raw: string }

/** One endpoint in the comparison, carrying whichever snapshot side has it. */
export interface CompareRow {
  endpointKey: string
  method: string
  path: string
  prev: ApiEndpointView | null
  next: ApiEndpointView | null
}

/** Full comparison model for a picked version pair. */
export interface CompareModel {
  added: CompareRow[]
  changed: CompareRow[]
  removed: CompareRow[]
  /** 'core-ledger' = diffSummary of an adjacent pair; 'snapshot-join' = derived here. */
  source: 'core-ledger' | 'snapshot-join'
}

/**
 * Parse an OpenAPI schema string into flat field rows for the comparison
 * panel. Object schemas expose their properties (required flags read from the
 * `required` array); primitive schemas collapse to one `(body)` row. Anything
 * unparsable degrades to { ok: false, raw } so the UI shows the original text
 * folded and labels it honestly instead of dumping broken JSON.
 */
export function parseSchemaFields(schemaJson: string | null | undefined): SchemaParseResult | null {
  if (schemaJson === null || schemaJson === undefined || schemaJson.trim() === '') return null
  let parsed: unknown
  try {
    parsed = JSON.parse(schemaJson)
  } catch {
    return { ok: false, raw: schemaJson }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { ok: false, raw: schemaJson }
  }
  const obj = parsed as Record<string, unknown>
  const props = obj.properties
  if (props && typeof props === 'object' && !Array.isArray(props)) {
    const requiredList = Array.isArray(obj.required) ? (obj.required as unknown[]).map(String) : []
    const fields: SchemaFieldRow[] = Object.entries(props as Record<string, unknown>).map(
      ([name, def]) => ({
        name,
        type: fieldTypeName(def),
        required: requiredList.includes(name),
      }),
    )
    return { ok: true, fields }
  }
  // primitive top-level schema (string/number/...) — one synthetic row
  if (typeof obj.type === 'string') {
    return { ok: true, fields: [{ name: '(body)', type: obj.type, required: false }] }
  }
  return { ok: false, raw: schemaJson }
}

/** Human type label for one property definition (handles $ref and arrays). */
function fieldTypeName(def: unknown): string {
  if (!def || typeof def !== 'object') return 'any'
  const d = def as Record<string, unknown>
  if (typeof d.$ref === 'string') return d.$ref.split('/').pop() || 'ref'
  if (d.type === 'array') {
    const items = d.items
    return `${fieldTypeName(items)}[]`
  }
  return typeof d.type === 'string' ? d.type : 'any'
}

function toRow(endpointKey: string, prev: ApiEndpointView | null, next: ApiEndpointView | null): CompareRow {
  const src = next ?? prev!
  const sp = endpointKey.split(' ')
  return {
    endpointKey,
    method: src?.method ?? sp[0] ?? '',
    path: src?.path ?? sp[1] ?? '',
    prev,
    next,
  }
}

function byKey(rows: ApiEndpointView[]): Map<string, ApiEndpointView> {
  const m = new Map<string, ApiEndpointView>()
  for (const r of rows) m.set(r.endpointKey, r)
  return m
}

/**
 * Build the compare model for a picked (prev, next) version pair.
 * Adjacent pairs (next.prevSpecId === prev.id) with a core diffSummary ledger
 * render that ledger verbatim — the page never overrules core's judgment.
 * Any other pair is derived here by endpointKey set difference plus
 * structureHash change detection, labeled 'snapshot-join' so the UI can say
 * what it is.
 */
export function buildVersionCompare(prev: ApiSpecView, next: ApiSpecView): CompareModel {
  const prevMap = byKey(prev.endpoints || [])
  const nextMap = byKey(next.endpoints || [])
  const adjacent = next.prevSpecId !== null && next.prevSpecId === prev.id

  if (adjacent && next.diffSummary) {
    const ledger = next.diffSummary
    return {
      added: [...ledger.added].sort().map((k) => toRow(k, prevMap.get(k) ?? null, nextMap.get(k) ?? null)),
      changed: [...ledger.changed].sort().map((k) => toRow(k, prevMap.get(k) ?? null, nextMap.get(k) ?? null)),
      removed: [...ledger.removed].sort().map((k) => toRow(k, prevMap.get(k) ?? null, nextMap.get(k) ?? null)),
      source: 'core-ledger',
    }
  }

  const added: CompareRow[] = []
  const removed: CompareRow[] = []
  const changed: CompareRow[] = []
  for (const [k, nx] of nextMap) {
    const pv = prevMap.get(k)
    if (!pv) added.push(toRow(k, null, nx))
    else if (pv.structureHash !== nx.structureHash) changed.push(toRow(k, pv, nx))
  }
  for (const [k, pv] of prevMap) {
    if (!nextMap.has(k)) removed.push(toRow(k, pv, null))
  }
  const keySort = (a: CompareRow, b: CompareRow) => a.endpointKey.localeCompare(b.endpointKey)
  return {
    added: added.sort(keySort),
    changed: changed.sort(keySort),
    removed: removed.sort(keySort),
    source: 'snapshot-join',
  }
}

/**
 * Step-level evidence anchor display (report A of the generation-accuracy batch).
 *
 * The stored value is the canonical jsonb text owned by the core single source
 * (CaseStepAnchorNormalizer: kind=elem page element / kind=source requirement
 * document blocks / kind=contract folded contract endpoint). This module only
 * READS that payload for display: it never re-validates, rewrites or invents an
 * anchor (R40 — the save door owns refusals, the page owns rendering).
 *
 * Honest-empty rule: anything the display cannot read (null, blank, unparsable
 * JSON, an unregistered kind) renders as "no anchor recorded" instead of a
 * guessed label, and never breaks the step table.
 */

export type StepAnchorKind = 'elem' | 'source' | 'contract'

/** Display shape of one step anchor (fields filled per kind, others left empty). */
export interface StepAnchorView {
  kind: StepAnchorKind
  /** elem: element names referenced by the step (single "ref" folds into this list) */
  refs: string[]
  /** source: requirement point id */
  requirementId: number | null
  /** source: document id the requirement came from */
  documentId: number | null
  /** source: original-text block ids behind the evidence */
  chunkIds: number[]
  /** contract: folded contract endpoint key */
  endpoint: string
}

/** Translate signature mirroring the vue-i18n composer (key + named params). */
export type AnchorTranslate = (key: string, named?: Record<string, string | number>) => string

const KNOWN_KINDS: readonly string[] = ['elem', 'source', 'contract']

function toTextArray(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return raw
    .filter((x): x is string => typeof x === 'string' && x.trim() !== '')
    .map((x) => x.trim())
}

function toNumber(raw: unknown): number | null {
  const value = typeof raw === 'number' ? raw : typeof raw === 'string' && raw.trim() !== '' ? Number(raw) : Number.NaN
  return Number.isFinite(value) ? value : null
}

function toNumberArray(raw: unknown): number[] {
  if (!Array.isArray(raw)) return []
  return raw.map(toNumber).filter((x): x is number => x !== null)
}

/**
 * Parse one stored anchor text.
 * @returns display shape, or null when there is nothing readable to show
 */
export function parseStepAnchor(raw: unknown): StepAnchorView | null {
  if (typeof raw !== 'string' || raw.trim() === '') return null
  let node: unknown
  try {
    node = JSON.parse(raw)
  } catch {
    return null
  }
  if (!node || typeof node !== 'object' || Array.isArray(node)) return null
  const obj = node as Record<string, unknown>
  const kind = typeof obj.kind === 'string' ? obj.kind.trim() : ''
  if (!KNOWN_KINDS.includes(kind)) return null
  const refs = toTextArray(obj.refs)
  const single = typeof obj.ref === 'string' ? obj.ref.trim() : ''
  if (single && !refs.includes(single)) refs.unshift(single)
  const sourceKind = kind === 'source'
  return {
    kind: kind as StepAnchorKind,
    refs: kind === 'elem' ? refs : [],
    requirementId: sourceKind ? toNumber(obj.requirementId) : null,
    documentId: sourceKind ? toNumber(obj.documentId) : null,
    chunkIds: sourceKind ? toNumberArray(obj.chunkIds) : [],
    endpoint: kind === 'contract' ? single : '',
  }
}

/**
 * One-line readable text for the step table (null = honest empty cell).
 * @param t locale translator of the calling component
 */
export function formatStepAnchor(raw: unknown, t: AnchorTranslate): string | null {
  const view = parseStepAnchor(raw)
  if (!view) return null
  if (view.kind === 'elem') {
    if (view.refs.length === 0) return null
    return t('case.stepAnchorElem', { refs: view.refs.join(t('case.stepAnchorListSep')) })
  }
  if (view.kind === 'contract') {
    return view.endpoint ? t('case.stepAnchorContract', { ref: view.endpoint }) : null
  }
  const parts: string[] = []
  if (view.requirementId !== null) parts.push(t('case.stepAnchorRequirement', { id: view.requirementId }))
  if (view.documentId !== null) parts.push(t('case.stepAnchorDocument', { id: view.documentId }))
  if (view.chunkIds.length > 0) parts.push(t('case.stepAnchorChunks', { ids: view.chunkIds.join(', ') }))
  return parts.length > 0 ? parts.join(' · ') : null
}

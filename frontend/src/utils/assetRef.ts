/**
 * Shared-asset overlay pure-logic kernel .
 *
 * Semantic sources (single-source red line, shapes are not invented here):
 * - catalog rows are the admin /shared-assets contract mirror (SharedAssetView
 * subset): core resolves the version chain and the published-head marker;
 * this module stays passive — it never re-resolves chains and never
 * refetches (R40);
 * - per-kind payload shapes follow the publish contract of the asset model:
 * case_fragment = step-array snapshot, action_component = one step object,
 * login_recipe / gen_function = text snapshots inserted verbatim into the
 * current field (a gen_function row carrying a `content` field is unwrapped
 * because the factory publishes its content snapshot);
 * - step-tree insertion itself belongs to the StepTree.vue
 * insertStepsAtSelection kernel — this file only produces the descriptor.
 * Complementary to utils/genRef.ts (grammar tokens) and utils/dataRef.ts
 * (pool keys): this file inserts asset content, not tokens.
 */
import type { ScopeStep } from '@/utils/stepScope'

/** Minimal structural view of a SharedAssetView row (loose for direct test fixtures). */
export interface AssetRowLike {
  id: number
  kind: string
  name: string
  ownerProjectName: string
  assetVersion: number
  /** 1 = published, 2 = withdrawn (chain level) */
  status: number
  /** true when this row is the current chain head */
  head: boolean
  /** content snapshot of the publishing project's entity */
  payloadJson: string
}

/** What a picked asset inserts: step skeletons, verbatim text, or nothing honest. */
export type AssetInsert =
  | { mode: 'steps'; steps: ScopeStep[] }
  | { mode: 'text'; text: string }
  | { mode: 'invalid' }

export interface AssetOption {
  assetRowId: number
  name: string
  ownerProjectName: string
  assetVersion: number
  kind: string
  insert: AssetInsert
}

export interface AssetOptionGroup {
  kind: string
  options: AssetOption[]
}

const STEP_KINDS = new Set(['case_fragment', 'action_component'])

/** Step-tree kinds land through insertStepsAtSelection; everything else is text. */
export function isStepKind(kind: string): boolean {
  return STEP_KINDS.has(kind)
}

function isStepShape(value: unknown): value is ScopeStep {
  return (
    !!value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    typeof (value as { action?: unknown }).action === 'string'
  )
}

function textOrSelf(raw: string): AssetInsert {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { mode: 'text', text: raw }
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const content = (parsed as Record<string, unknown>).content
    if (typeof content === 'string' && content.trim()) return { mode: 'text', text: content }
  }
  return { mode: 'text', text: raw }
}

/** One catalog row -> insert descriptor (fail-closed: unshapable payloads are invalid, never a half-built insert). */
export function assetInsertOf(row: AssetRowLike): AssetInsert {
  const raw = (row.payloadJson ?? '').trim()
  if (!raw) return { mode: 'invalid' }
  if (row.kind === 'case_fragment') {
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      return { mode: 'invalid' }
    }
    if (!Array.isArray(parsed) || !parsed.every(isStepShape)) return { mode: 'invalid' }
    return { mode: 'steps', steps: parsed as ScopeStep[] }
  }
  if (row.kind === 'action_component') {
    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      return { mode: 'invalid' }
    }
    if (!isStepShape(parsed)) return { mode: 'invalid' }
    return { mode: 'steps', steps: [parsed] }
  }
  if (row.kind === 'login_recipe') return { mode: 'text', text: raw }
  if (row.kind === 'gen_function') return textOrSelf(raw)
  return { mode: 'invalid' }
}

/** Published heads only, grouped by kind in first-seen order; row order kept inside a group. */
export function buildAssetGroups(rows: AssetRowLike[]): AssetOptionGroup[] {
  const groups: AssetOptionGroup[] = []
  const byKind = new Map<string, AssetOptionGroup>()
  for (const row of rows) {
    if (row.status !== 1 || !row.head) continue
    let group = byKind.get(row.kind)
    if (!group) {
      group = { kind: row.kind, options: [] }
      byKind.set(row.kind, group)
      groups.push(group)
    }
    group.options.push({
      assetRowId: row.id,
      name: row.name,
      ownerProjectName: row.ownerProjectName,
      assetVersion: row.assetVersion,
      kind: row.kind,
      insert: assetInsertOf(row),
    })
  }
  return groups
}

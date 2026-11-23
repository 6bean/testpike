/**
 * Step-table index-shift primitives .
 *
 * The perf wizard keeps per-row editor side-state in index-keyed containers
 * (collapse flags, half-typed header drafts, the dataset-token target). Any
 * bulk operation on the step table — delete, insert-after, duplicate, move —
 * that forgets to remap this side-state leaves the next row wearing the
 * removed row's drafts (the shift trap). These helpers are the single
 * remap source so every operation path stays honest (red line 6: no second
 * copy of the shift logic).
 *
 * Row identity for bulk selection: rows are plain reactive objects with no
 * id field, so a WeakMap hands each object one stable numeric uid. Selection
 * keyed by uid survives filtering and reordering, where array indexes would
 * silently point at a different row.
 */

/** Index-keyed side-state that must follow row movements. */
export interface StepAux<TSlot> {
  /** Collapsed/open flag per row position. */
  open: boolean[]
  /** Half-typed header draft keyed by row position. */
  drafts: Record<number, { k: string; v: string }>
  /** Last focused parameter slot (null = no insert target). */
  target: { i: number; slot: TSlot } | null
}

/**
 * A row at position `at` was removed: drop its slot and shift everything
 * behind it up by one. Operates in place (the callers hold refs).
 */
export function shrinkStepAux<TSlot>(aux: StepAux<TSlot>, at: number): void {
  aux.open.splice(at, 1)
  const shifted: Record<number, { k: string; v: string }> = {}
  for (const key of Object.keys(aux.drafts)) {
    const idx = Number(key)
    if (idx < at) shifted[idx] = aux.drafts[idx]
    else if (idx > at) shifted[idx - 1] = aux.drafts[idx]
  }
  for (const key of Object.keys(aux.drafts)) delete aux.drafts[Number(key)]
  Object.assign(aux.drafts, shifted)
  if (aux.target) {
    if (aux.target.i === at) aux.target = null
    else if (aux.target.i > at) aux.target = { i: aux.target.i - 1, slot: aux.target.slot }
  }
}

/**
 * A row was inserted at position `at`: give it a closed fold and shift
 * everything from `at` down by one (mirror of shrink).
 */
export function growStepAux<TSlot>(aux: StepAux<TSlot>, at: number): void {
  aux.open.splice(at, 0, false)
  const shifted: Record<number, { k: string; v: string }> = {}
  for (const key of Object.keys(aux.drafts)) {
    const idx = Number(key)
    shifted[idx < at ? idx : idx + 1] = aux.drafts[idx]
  }
  for (const key of Object.keys(aux.drafts)) delete aux.drafts[Number(key)]
  Object.assign(aux.drafts, shifted)
  if (aux.target && aux.target.i >= at) {
    aux.target = { i: aux.target.i + 1, slot: aux.target.slot }
  }
}

/** Two positions exchanged (move up/down): folds, drafts and target follow. */
export function swapStepAux<TSlot>(aux: StepAux<TSlot>, a: number, b: number): void {
  if (a === b) return
  const t = aux.open[a]
  aux.open[a] = aux.open[b]
  aux.open[b] = t
  const da = aux.drafts[a]
  const db = aux.drafts[b]
  if (db) aux.drafts[a] = db
  else delete aux.drafts[a]
  if (da) aux.drafts[b] = da
  else delete aux.drafts[b]
  if (aux.target) {
    if (aux.target.i === a) aux.target = { i: b, slot: aux.target.slot }
    else if (aux.target.i === b) aux.target = { i: a, slot: aux.target.slot }
  }
}

/** A step row shape subset that duplicate needs (fields stay open-ended). */
export type DuplicableStepRow = Record<string, unknown>

/**
 * Copy a step row with its parameters: JSON deep copy so headers/body/expect
 * never share object identity with the source (editing the copy must not
 * bleed into the original).
 */
export function duplicateStepRow<T extends DuplicableStepRow>(row: T): T {
  return JSON.parse(JSON.stringify(row)) as T
}

const uidByKey = new WeakMap<object, number>()
let uidSeq = 0

/** Stable per-object uid for bulk selection keys (never array indexes). */
export function stepUidOf(row: object): number {
  let uid = uidByKey.get(row)
  if (uid === undefined) {
    uid = ++uidSeq
    uidByKey.set(row, uid)
  }
  return uid
}

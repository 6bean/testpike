/**
 * Stateful-scenario graph reader/writer (AG batch the scenario
 * editor's state-graph visualization and edit form consume this).
 *
 * Posture: this module turns a stored declaration into a renderable/editable
 * model and serializes an edit back into the kernel-frozen key set. It never
 * decides legality — the shared kernel (common MockScenarioRules, core save
 * chain) owns that and answers with plain words. A declaration that cannot
 * even be read structurally (blank / not JSON / not an object / empty states)
 * comes back as an honest refusal carrying no half-graph, and a machine whose
 * transitions reference undeclared states keeps those edges visible as
 * "dangling" so the editor can point the user at what the save will refuse —
 * hiding a broken edge would be pretending.
 *
 * Declaration shape (frozen by the kernel): { states: [...], initial?,
 * scope?, transitions?: [{from,to}] } with initial defaulting to the first
 * declared state and scope defaulting to the session lane (M-c ruling).
 */

/** One transition edge as declared (from -> to). */
export interface ScenarioEdge {
  from: string
  to: string
}

/** Renderable/editable form of one machine declaration. */
export interface ScenarioGraph {
  /** Declared state labels in declaration order (duplicates folded to first). */
  states: string[]
  /** State a fresh lane starts in (kernel default = first declared state). */
  initial: string
  /** Lane spectrum: session | role | global (unknown values pass through raw). */
  scope: string
  /** Edges whose endpoints are both declared states, in declaration order. */
  transitions: ScenarioEdge[]
  /** Edges referencing an undeclared endpoint — visible so the editor can fix them. */
  dangling: ScenarioEdge[]
}

/** Why a declaration could not be read structurally (never a silent default). */
export type ScenarioReadFailure =
  | 'blank'
  | 'not-json'
  | 'not-object'
  | 'no-states'
  | 'initial-undeclared'

export type ScenarioReadResult =
  | { ok: true; graph: ScenarioGraph }
  | { ok: false; reason: ScenarioReadFailure }

/** Read a stored definition JSON into an editable graph model. */
export function readScenarioGraph(definitionJson: string | null | undefined): ScenarioReadResult {
  const text = (definitionJson ?? '').trim()
  if (!text) return { ok: false, reason: 'blank' }
  let root: unknown
  try {
    root = JSON.parse(text)
  } catch {
    return { ok: false, reason: 'not-json' }
  }
  if (root === null || typeof root !== 'object' || Array.isArray(root)) {
    return { ok: false, reason: 'not-object' }
  }
  const obj = root as Record<string, unknown>
  const rawStates = Array.isArray(obj.states) ? obj.states : null
  if (!rawStates || rawStates.length === 0) return { ok: false, reason: 'no-states' }
  // Only textual labels are renderable; anything else is not our decision to
  // coerce — the core names the offender in plain words when it refuses.
  const states: string[] = []
  for (const s of rawStates) {
    if (typeof s === 'string' && s && !states.includes(s)) states.push(s)
  }
  if (!states.length) return { ok: false, reason: 'no-states' }
  const initial = typeof obj.initial === 'string' && obj.initial ? obj.initial : states[0]
  if (!states.includes(initial)) return { ok: false, reason: 'initial-undeclared' }
  const scope = typeof obj.scope === 'string' && obj.scope ? obj.scope : 'session'
  const transitions: ScenarioEdge[] = []
  const dangling: ScenarioEdge[] = []
  if (Array.isArray(obj.transitions)) {
    for (const t of obj.transitions) {
      if (t === null || typeof t !== 'object') continue
      const from = (t as Record<string, unknown>).from
      const to = (t as Record<string, unknown>).to
      if (typeof from !== 'string' || typeof to !== 'string' || !from || !to) continue
      const edge: ScenarioEdge = { from, to }
      if (states.includes(from) && states.includes(to)) transitions.push(edge)
      else dangling.push(edge)
    }
  }
  return { ok: true, graph: { states, initial, scope, transitions, dangling } }
}

/** The editable model shape the writer accepts (dangling edges are not stored —
 *  the editor must resolve them into declared states before it can save). */
export interface ScenarioWriteModel {
  states: string[]
  initial: string
  scope: string
  transitions: ScenarioEdge[]
}

/** Serialize an edited model back into the kernel-frozen key set (exact four keys). */
export function writeScenarioDefinition(model: ScenarioWriteModel): string {
  return JSON.stringify(
    {
      states: model.states,
      initial: model.initial,
      scope: model.scope,
      transitions: model.transitions.map((e) => ({ from: e.from, to: e.to })),
    },
    null,
    2,
  )
}

/**
 * i18n key of one lane spectrum (M-c ruling). Unknown values return '' so the
 * caller renders the raw text honestly instead of a wrong label — the core's
 * save chain is what refuses an unknown scope, with words, never this map.
 */
export function laneSpectrumLabel(scope: string): string {
  if (scope === 'session') return 'mockSpaces.laneSession'
  if (scope === 'role') return 'mockSpaces.laneRole'
  if (scope === 'global') return 'mockSpaces.laneGlobal'
  return ''
}

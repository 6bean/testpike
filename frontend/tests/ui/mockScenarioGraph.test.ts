/**
 * RED anchor for the stateful-scenario graph reader (AG batch
 * the editor's state-graph visualization consumes this).
 *
 * Posture mirrors the paste-curl parser: this module only READS a declaration
 * into a renderable shape and SERIALIZES an edited shape back. Legality of a
 * machine is decided once at the core save chain (MockScenarioRules kernel) —
 * the frontend never re-decides, so an unparseable declaration comes back as
 * an honest refusal carrying the raw text, never a silently "fixed" graph.
 *
 * Declaration shape (frozen by the kernel): { states: [...], initial?,
 * scope?, transitions?: [{from,to}] } with initial defaulting to the first
 * declared state and scope defaulting to the session lane.
 */
import { describe, it, expect } from 'vitest'
import {
  readScenarioGraph,
  writeScenarioDefinition,
  laneSpectrumLabel,
} from '../../src/utils/mockScenarioGraph'

describe('scenario graph reader', () => {
  it('reads states in declaration order with the first state as default initial', () => {
    const g = readScenarioGraph('{"states":["CREATED","PAID","SHIPPED"]}')
    if (!g.ok) throw new Error(`expected ok but got reason=${g.reason}`)
    expect(g.graph.states).toEqual(['CREATED', 'PAID', 'SHIPPED'])
    expect(g.graph.initial).toBe('CREATED')
    expect(g.graph.scope).toBe('session')
    expect(g.graph.transitions).toEqual([])
  })

  it('honours an explicit initial and scope and keeps transition pairs verbatim', () => {
    const g = readScenarioGraph(
      '{"states":["A","B"],"initial":"B","scope":"role","transitions":[{"from":"A","to":"B"},{"from":"B","to":"A"}]}',
    )
    if (!g.ok) throw new Error(`expected ok but got reason=${g.reason}`)
    expect(g.graph.initial).toBe('B')
    expect(g.graph.scope).toBe('role')
    expect(g.graph.transitions).toEqual([
      { from: 'A', to: 'B' },
      { from: 'B', to: 'A' },
    ])
  })

  it('drops transition rows that are not object pairs instead of rendering ghosts', () => {
    const g = readScenarioGraph('{"states":["A","B"],"transitions":[{"from":"A","to":"B"},"x",42,{}]}')
    if (!g.ok) throw new Error(`expected ok but got reason=${g.reason}`)
    expect(g.graph.transitions).toEqual([{ from: 'A', to: 'B' }])
  })

  it('refuses blank text, non-JSON text, a JSON array and an empty state set — honestly', () => {
    expect(readScenarioGraph('')).toMatchObject({ ok: false, reason: 'blank' })
    expect(readScenarioGraph('not json')).toMatchObject({ ok: false, reason: 'not-json' })
    expect(readScenarioGraph('["A"]')).toMatchObject({ ok: false, reason: 'not-object' })
    expect(readScenarioGraph('{"states":[]}')).toMatchObject({ ok: false, reason: 'no-states' })
    expect(readScenarioGraph('{"states":"A"}')).toMatchObject({ ok: false, reason: 'no-states' })
  })

  it('refuses when the declared initial is not among the states (a ghost marker would lie)', () => {
    expect(readScenarioGraph('{"states":["A"],"initial":"Z"}')).toMatchObject({
      ok: false,
      reason: 'initial-undeclared',
    })
  })

  it('keeps an undeclared transition endpoint visible as a dangling edge for the drawer to fix', () => {
    // The core refuses such a machine at save time; the editor must SHOW the
    // broken endpoint (dangling) rather than hide it — hiding it would let a
    // user believe the graph is complete while the save keeps refusing.
    const g = readScenarioGraph('{"states":["A"],"transitions":[{"from":"A","to":"GONE"}]}')
    if (!g.ok) throw new Error(`expected ok but got reason=${g.reason}`)
    expect(g.graph.dangling).toEqual([{ from: 'A', to: 'GONE' }])
  })

  it('trims duplicate states down to first occurrence order for rendering', () => {
    const g = readScenarioGraph('{"states":["A","B","A"]}')
    if (!g.ok) throw new Error(`expected ok but got reason=${g.reason}`)
    expect(g.graph.states).toEqual(['A', 'B'])
  })
})

describe('scenario definition writer', () => {
  it('serializes the edited model back into the kernel-frozen key set', () => {
    const json = writeScenarioDefinition({
      states: ['CREATED', 'PAID'],
      initial: 'CREATED',
      scope: 'session',
      transitions: [{ from: 'CREATED', to: 'PAID' }],
    })
    const parsed = JSON.parse(json)
    expect(parsed).toEqual({
      states: ['CREATED', 'PAID'],
      initial: 'CREATED',
      scope: 'session',
      transitions: [{ from: 'CREATED', to: 'PAID' }],
    })
  })

  it('round-trips a read declaration unchanged through write', () => {
    const source = '{"states":["A","B"],"initial":"B","scope":"role","transitions":[{"from":"A","to":"B"}]}'
    const g = readScenarioGraph(source)
    if (!g.ok) throw new Error(`expected ok but got reason=${g.reason}`)
    expect(JSON.parse(writeScenarioDefinition(g.graph))).toEqual(JSON.parse(source))
  })

  it('omits nothing the editor edited: an empty transition list writes as an empty array', () => {
    const json = writeScenarioDefinition({ states: ['A'], initial: 'A', scope: 'global', transitions: [] })
    expect(JSON.parse(json).transitions).toEqual([])
  })
})

describe('lane spectrum label', () => {
  it('maps the three M-c spectra onto i18n keys (zero hard-coded wording) and keeps unknown raw values honest', () => {
    expect(laneSpectrumLabel('session')).toBe('mockSpaces.laneSession')
    expect(laneSpectrumLabel('role')).toBe('mockSpaces.laneRole')
    expect(laneSpectrumLabel('global')).toBe('mockSpaces.laneGlobal')
    // An unknown scope must not be swallowed into a wrong label — the page
    // renders the raw value so the user sees what the machine actually says.
    expect(laneSpectrumLabel('wat')).toBe('')
  })
})

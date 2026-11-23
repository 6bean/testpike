/**
 * UI Flow canvas view L2 contract (W5 app-automation phase two, segment B3):
 * - pure lookup single source = @/utils/flowCanvasProjection — the clicked-node →
 * action-step mapping rides the server-emitted `nodes` pairs ONLY
 * (core FlowchartMermaidAssembler owns the traversal; re-deriving it here would
 * duplicate that rule — R40), so every helper is table-lookup with honest nulls;
 * - wiring guard: FlowCanvasView.vue must consume that single source plus the
 * shared mermaid renderer (utils/mermaidRender) and the flowchart endpoint
 * (api/case.getCaseFlowchart) — no second mermaid bootstrap in the page;
 * - registration contract: route leaf + menu title + synonyms + zh/en page copy,
 * every new page rides the route list (project red line: all new pages registered).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  clickedNodeIdFromElementId,
  nodeIdForStepIndex,
  stepIndexForNode,
} from '@/utils/flowCanvasProjection'

const PAGE_SRC = readFileSync(
  new URL('../../src/views/appauto/FlowCanvasView.vue', import.meta.url),
  'utf-8',
)
const ROUTES_SRC = readFileSync(
  new URL('../../src/router/routes.ts', import.meta.url),
  'utf-8',
)
const ZH_SRC = readFileSync(
  new URL('../../src/utils/locales/zh-CN.ts', import.meta.url),
  'utf-8',
)
const EN_SRC = readFileSync(
  new URL('../../src/utils/locales/en-US.ts', import.meta.url),
  'utf-8',
)
const SYNONYMS_SRC = readFileSync(
  new URL('../../src/config/menuSynonyms.ts', import.meta.url),
  'utf-8',
)

const NODES = [
  { id: 's1', stepIndex: 1 },
  { id: 's2', stepIndex: 2 },
  { id: 's7', stepIndex: 7 },
]

describe('flowCanvasProjection.stepIndexForNode', () => {
  it('resolves a clicked node through the emitted pairs only', () => {
    expect(stepIndexForNode(NODES, 's7')).toBe(7)
  })

  it('returns null for an id the assembler never emitted (helper nodes excluded upstream)', () => {
    expect(stepIndexForNode(NODES, 'b1')).toBeNull()
  })

  it('returns null on an empty table instead of inventing an index', () => {
    expect(stepIndexForNode([], 's1')).toBeNull()
  })
})

describe('flowCanvasProjection.nodeIdForStepIndex', () => {
  it('reverse-resolves a step to its canvas node for chip highlighting', () => {
    expect(nodeIdForStepIndex(NODES, 2)).toBe('s2')
  })

  it('returns null for a step the projection does not carry', () => {
    expect(nodeIdForStepIndex(NODES, 99)).toBeNull()
  })
})

describe('flowCanvasProjection.clickedNodeIdFromElementId', () => {
  it('unwraps the mermaid 11 node id shape (render prefix + trailing counter)', () => {
    // Real rendered ids sampled from a live browser run: `flow-canvas-1-flowchart-s3-7`.
    expect(clickedNodeIdFromElementId('flow-canvas-1-flowchart-s3-7')).toBe('s3')
    expect(clickedNodeIdFromElementId('flow-canvas-2-flowchart-s10-9')).toBe('s10')
  })

  it('unwraps the legacy bare prefix shape', () => {
    expect(clickedNodeIdFromElementId('flowchart-s3')).toBe('s3')
  })

  it('ignores edge elements (L_s1_s2_edge0 style ids are not steps)', () => {
    expect(clickedNodeIdFromElementId('L_s1_s2_edge0')).toBeNull()
  })

  it('ignores marker defs/labels/null when the click misses a node shape', () => {
    // Arrow marker ids embed `flowchart` after an underscore with no trailing
    // counter — they must not resolve to a step.
    expect(clickedNodeIdFromElementId('flow-canvas-1_flowchart-v2-pointEnd')).toBeNull()
    expect(clickedNodeIdFromElementId('flowchart-s3-icon')).toBe('s3-icon')
    expect(clickedNodeIdFromElementId(undefined)).toBeNull()
    expect(clickedNodeIdFromElementId(null)).toBeNull()
  })
})

describe('FlowCanvasView wiring guard (single-source consumption)', () => {
  it('resolves clicked nodes through the projection helper, not a local traversal', () => {
    expect(PAGE_SRC).toContain('stepIndexForNode')
  })

  it('renders with the shared mermaid renderer (no second bootstrap in the page)', () => {
    expect(PAGE_SRC).toContain('renderMermaidSvg')
    expect(PAGE_SRC).not.toContain('mermaid.initialize')
  })

  it('loads the projection through the mirrored flowchart endpoint', () => {
    expect(PAGE_SRC).toContain('getCaseFlowchart')
  })

  it('keeps the unified page container and canvas anchors (R17)', () => {
    expect(PAGE_SRC).toContain('page-card')
    expect(PAGE_SRC).toContain('data-test="flow-canvas-svg"')
    expect(PAGE_SRC).toContain('data-test="flow-canvas-step-card"')
  })

  it('offers both follow-up actions from a clicked step: jump to the case detail and capture from device', () => {
    expect(PAGE_SRC).toContain('data-test="flow-canvas-jump-btn"')
    expect(PAGE_SRC).toContain('data-test="flow-canvas-capture-btn"')
  })
})

describe('registration contract (every new page rides route list + menu + i18n)', () => {
  it('route leaf registered under the appauto group with a menu title key', () => {
    expect(ROUTES_SRC).toContain("path: 'appauto/flow-canvas'")
    expect(ROUTES_SRC).toContain('FlowCanvasView.vue')
    expect(ROUTES_SRC).toContain("titleKey: 'layout.flowCanvas'")
  })

  it('zh and en menu titles present symmetrically', () => {
    expect(ZH_SRC).toContain("flowCanvas: '")
    expect(EN_SRC).toContain("flowCanvas: '")
  })

  it('page copy namespace exists in both locales', () => {
    expect(ZH_SRC).toContain('flowCanvas: {')
    expect(EN_SRC).toContain('flowCanvas: {')
  })

  it('menu search synonyms registered for the new leaf', () => {
    expect(SYNONYMS_SRC).toContain("'/appauto/flow-canvas':")
  })
})

/**
 * Pure SVG layout model for one stateful-scenario graph .
 *
 * Extracted from MockDocsView so the docs-page summary surface and the new
 * scenario detail page render the same picture from one implementation
 * (iron rule: logic reused twice lives in one shared module, never a fork).
 * The layout is dumb math — read/write of declarations stays in
 * mockScenarioGraph, legality stays with the core save gate.
 */
import type { ScenarioGraph } from './mockScenarioGraph'

export interface ScenarioLayoutNode {
  name: string
  x: number
  y: number
  initial: boolean
}

export interface ScenarioLayoutEdge {
  d: string
  dangling: boolean
}

export interface ScenarioLayoutGhost {
  label: string
  x: number
  y: number
}

/** Pure layout model of one graph (dumb template, no math in the DOM layer). */
export interface ScenarioLayout {
  width: number
  height: number
  nodes: ScenarioLayoutNode[]
  edges: ScenarioLayoutEdge[]
  ghosts: ScenarioLayoutGhost[]
}

/** Empty layout for an unreadable declaration — still a valid render target. */
export const EMPTY_LAYOUT: ScenarioLayout = { width: 260, height: 104, nodes: [], edges: [], ghosts: [] }

const NODE_R = 22
const GAP_X = 58
const PAD = 30

/** Straight/looped/arc edge path between two node centres, trimmed to node rims. */
export function edgePath(x1: number, y1: number, x2: number, y2: number): string {
  const dx = x2 - x1
  const dy = y2 - y1
  const len = Math.hypot(dx, dy) || 1
  const sx = x1 + (dx / len) * (NODE_R + 2)
  const sy = y1 + (dy / len) * (NODE_R + 2)
  const ex = x2 - (dx / len) * (NODE_R + 4)
  const ey = y2 - (dy / len) * (NODE_R + 4)
  if (Math.abs(x2 - x1) < 1 && Math.abs(y2 - y1) < 1) {
    // self loop: a small circle above the node
    return `M ${sx} ${sy - NODE_R} q 18 -26 36 0`
  }
  const forward = x2 > x1
  if (Math.abs(y2 - y1) < 1 && forward) {
    return `M ${sx} ${sy} L ${ex} ${ey}`
  }
  // backward edges: an arc that stays clear of the row
  const lift = 34
  return `M ${sx} ${sy} Q ${(sx + ex) / 2} ${Math.min(sy, ey) - lift} ${ex} ${ey}`
}

/** Place states on one row, route transitions, pin dangling endpoints as ghosts. */
export function buildLayout(graph: ScenarioGraph): ScenarioLayout {
  const cols = Math.max(1, graph.states.length)
  const width = Math.max(
    260,
    PAD * 2 + cols * NODE_R * 2 + (cols - 1) * GAP_X + (graph.dangling.length ? 120 : 0),
  )
  const y = PAD + NODE_R
  const nodes = graph.states.map((name, i) => ({
    name,
    x: PAD + NODE_R + i * (NODE_R * 2 + GAP_X),
    y,
    initial: name === graph.initial,
  }))
  const pos = new Map(nodes.map((n) => [n.name, n]))
  const edges: ScenarioLayoutEdge[] = graph.transitions.map((e) => {
    const a = pos.get(e.from)
    const b = pos.get(e.to)
    if (!a || !b) return { d: '', dangling: false }
    return { d: edgePath(a.x, a.y, b.x, b.y), dangling: false }
  })
  const ghosts: ScenarioLayoutGhost[] = []
  graph.dangling.forEach((e, i) => {
    // an undeclared target gets a ghost dot and a dashed arrow from its
    // declared source (or the left margin) — a broken edge is never hidden
    const gx = width - PAD - 40
    const gy = y + (i % 2) * (NODE_R * 2 + 26)
    ghosts.push({ label: e.to, x: gx, y: gy })
    const a = pos.get(e.from)
    const fromX = a ? a.x + NODE_R + 2 : PAD
    const fromY = a ? a.y : y
    edges.push({ d: edgePath(fromX, fromY, gx - 10, gy), dangling: true })
  })
  const height = y + NODE_R + PAD + (graph.dangling.length > 1 ? 50 : 0)
  return { width, height, nodes, edges, ghosts }
}

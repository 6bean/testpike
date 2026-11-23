/**
 * Canvas node to step projection (W5 app-automation phase two, segment B3).
 *
 * The core flowchart assembler is the single source of the node numbering and the
 * document traversal (see admin FlowchartView.nodes emitted by GET /cases/{id}/flowchart).
 * This module only table-looks-up those emitted pairs — re-walking the action tree here
 * would duplicate the assembler rule (R40), so every helper returns an honest null when
 * the mapping does not exist (helper nodes such as branch labels never appear upstream).
 */
import type { CaseFlowchartNode } from '@/api/case'

/** Resolve a clicked canvas node to the action step it projects (lookup only, null when unmapped). */
export function stepIndexForNode(
  nodes: readonly CaseFlowchartNode[],
  nodeId: string | null | undefined,
): number | null {
  if (!nodeId) return null
  const hit = nodes.find((n) => n.id === nodeId)
  return hit ? hit.stepIndex : null
}

/** Reverse lookup: locate the canvas node id carrying a given step (used to focus a node from a step anchor). */
export function nodeIdForStepIndex(
  nodes: readonly CaseFlowchartNode[],
  stepIndex: number | null | undefined,
): string | null {
  if (stepIndex == null) return null
  const hit = nodes.find((n) => n.stepIndex === stepIndex)
  return hit ? hit.id : null
}

/**
 * Unwrap a rendered mermaid element id back to the logical node id.
 * Mermaid 11 emits each flowchart node group with an id shaped
 * `<renderId>-flowchart-<nodeId>-<counter>` (for example
 * `flow-canvas-1-flowchart-s3-7`); legacy renders used the bare
 * `flowchart-<nodeId>` form. Both shapes parse here — the optional render
 * prefix and the trailing counter are trimmed, everything else (edge ids like
 * `L_s1_s2_edge0`, unknown strings) resolves to an honest null so a stray
 * click never fakes a selection.
 */
export function clickedNodeIdFromElementId(
  elementId: string | null | undefined,
): string | null {
  if (!elementId) return null
  const hit = /^(?:[\w-]+-)?flowchart-(.+?)(?:-\d+)?$/.exec(elementId)
  return hit ? hit[1] : null
}

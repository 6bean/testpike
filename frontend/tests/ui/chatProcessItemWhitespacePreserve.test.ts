/**
 * stage 4 — analysis-process inter-word space preservation contract.
 *
 * Defect under test (vision round 6, evidence pack on disk): the model's
 * reasoning stream arrives as hundreds of tiny fragments and 127 of the 259
 * reason frames of the pinned sample carry a leading space on the wire and
 * in the ledger (write side verified byte-for-byte, see PROGRESS relay).
 * The reducer folds fragments into paragraph entries without trimming, so
 * the text model is intact; the loss happened only at render time because
 * `.chat-process-item` declared no `white-space` rule and the default
 * visible while still wrapping long paragraphs at word boundaries
 * (`pre-wrap` would hard-break every fragment's own newline mid-line).
 *
 * Contract layers pinned here:
 * 1. reducer fold preserves inter-fragment spaces (no trim on the
 * concatenation path; the model's newline stays the paragraph break);
 * 2. ChatPanel style block declares the preservation rule for process
 * items (raw-file assertion precedent = chatBadgeDiagnosis/chatDockResize).
 */
import { describe, expect, it } from 'vitest'
import panelRaw from '@/components/ChatPanel.vue?raw'
import {
  applyFrameToTurn,
  createAssistantTurn,
} from '@/composables/chatDockReducer'

function frame(event: string, data: string, id: number | null = 1) {
  return { event, id, data }
}

describe('reason fragment fold keeps inter-word spaces', () => {
  it('a fragment starting with a space grows the open paragraph verbatim', () => {
    const turn = createAssistantTurn(1)
    applyFrameToTurn(turn, frame('reason', '所以'))
    applyFrameToTurn(turn, frame('reason', ' 这个用例'))
    applyFrameToTurn(turn, frame('reason', ' 需要澄清'))
    expect(turn.process).toHaveLength(1)
    expect(turn.process[0].text).toBe('所以 这个用例 需要澄清')
  })

  it('a mid-fragment space survives when no paragraph break is involved', () => {
    const turn = createAssistantTurn(2)
    applyFrameToTurn(turn, frame('reason', 'total 0 / covered 0'))
    expect(turn.process[0].text).toBe('total 0 / covered 0')
  })

  it('the model newline still closes the paragraph and opens a fresh one', () => {
    const turn = createAssistantTurn(3)
    applyFrameToTurn(turn, frame('reason', '第一段结论'))
    applyFrameToTurn(turn, frame('reason', '\n第二段开头'))
    expect(turn.process.map((p) => p.text)).toEqual(['第一段结论', '第二段开头'])
    expect(turn.process[0].closed).toBe(true)
  })
})

describe('process item render preserves whitespace', () => {
  const styleBlock = panelRaw.slice(panelRaw.indexOf('<style'))

  it('declares break-spaces on the process item rule', () => {
    const rule = /(\.chat-process-item[^{]*\{[^}]*\})/.exec(styleBlock)
    expect(rule, '.chat-process-item rule must exist').not.toBeNull()
    expect(rule![1]).toMatch(/white-space:\s*break-spaces/)
  })

  it('process items render the folded entry text directly', () => {
    // The item's own text node stays the raw interpolation (never a trimmed
    // wrapper). Since the steering echo may prefix it with one chip span
    // for the user-inject kind — the prefix is allowed, the raw text node is
    // still what renders, so this contract never silently disappears.
    expect(panelRaw).toMatch(
      /class="chat-process-item"[^>]*>(?:<span[\s\S]{0,200}?<\/span>)?\{\{ p\.text \}\}/,
    )
  })
})

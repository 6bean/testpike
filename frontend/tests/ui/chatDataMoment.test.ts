/**
 * Behaviour kernel round-2 item 5 (front half) — data-moment mini-label.
 *
 * The server stamps the grounded pool's as-of moment onto the retrieval
 * state frame's meta side-channel ({"dataMoment":"<ISO offset datetime>"});
 * the dock folds it at the ONE folding point (chatDockReducer) into
 * turn.dataMoment, pre-formatted through the shared local formatter, and
 * ChatPanel renders a small "as of ..." line under the answer.
 *
 * Contract spectra here:
 *  - a state frame carrying a usable meta folds the formatted stamp and
 *    STILL keeps its human sentence in the process area (side channel, not
 *    a replacement — same posture as the flow-guide/chart trace fold);
 *  - no meta / malformed JSON / absent member / non-string member leaves
 *    the turn untouched: the label never invents a clock;
 *  - only state frames fold the member — a cite frame with the same meta
 *    never touches it (no second folding semantics);
 *  - a fresher retrieval frame replaces the earlier stamp (last-wins);
 *  - fresh turns start label-free, and reloaded rows (which never carry
 *    the field) stay honest no-label;
 *  - panel wiring + bilingual locale keys exist (zero CJK in the panel
 *    source, same rule as the suggest-chip precedent).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { applyFrameToTurn, createAssistantTurn } from '@/composables/chatDockReducer'
import { formatLocalDateTime } from '@/utils/localDateTime'
import panelRaw from '@/components/ChatPanel.vue?raw'

const MOMENT = '2026-11-06T10:00:00+08:00'
const RETRIEVAL_LINE = '检索完成：命中 3 条来源材料'

function stateFrame(meta?: string) {
  return { event: 'state', id: 1, data: RETRIEVAL_LINE, meta } as Parameters<typeof applyFrameToTurn>[1]
}

describe('retrieval frame data-moment fold (single folding point)', () => {
  it('a state frame with a usable dataMoment meta folds the formatted stamp and keeps the process line', () => {
    const turn = createAssistantTurn(1)
    applyFrameToTurn(turn, stateFrame(JSON.stringify({ dataMoment: MOMENT })))
    expect(turn.dataMoment).toBe(formatLocalDateTime(MOMENT))
    expect(turn.process).toEqual([{ kind: 'state', text: RETRIEVAL_LINE }])
  })

  it('a state frame without meta leaves the turn label-free', () => {
    const turn = createAssistantTurn(2)
    applyFrameToTurn(turn, stateFrame())
    expect(turn.dataMoment ?? null).toBeNull()
  })

  it('malformed meta, absent member and non-string member all fold to no label', () => {
    for (const meta of ['{not json', '{}', '{"dataMoment":17}']) {
      const turn = createAssistantTurn(3)
      applyFrameToTurn(turn, stateFrame(meta))
      expect(turn.dataMoment ?? null).toBeNull()
    }
  })

  it('a non-state frame carrying the member never folds it (no second folding semantics)', () => {
    const turn = createAssistantTurn(4)
    applyFrameToTurn(turn, {
      event: 'cite', id: 1, data: '[C1] x', meta: JSON.stringify({ dataMoment: MOMENT }),
    } as Parameters<typeof applyFrameToTurn>[1])
    expect(turn.dataMoment ?? null).toBeNull()
  })

  it('a fresher retrieval frame replaces the earlier stamp (last-wins)', () => {
    const turn = createAssistantTurn(5)
    applyFrameToTurn(turn, stateFrame(JSON.stringify({ dataMoment: MOMENT })))
    applyFrameToTurn(turn, stateFrame(JSON.stringify({ dataMoment: '2026-11-05T09:00:00+08:00' })))
    expect(turn.dataMoment).toBe(formatLocalDateTime('2026-11-05T09:00:00+08:00'))
  })

  it('fresh turns start without a stamp', () => {
    expect(createAssistantTurn(6).dataMoment ?? null).toBeNull()
  })
})

describe('panel wiring and bilingual locales (display surface)', () => {
  it('ChatPanel renders the stamp under the answer through the locale key (zero CJK in source)', () => {
    expect(panelRaw).toContain('chat-data-moment')
    expect(panelRaw).toContain("data-test=\"chat-data-moment\"")
    expect(panelRaw).toContain("t('layout.chatDock.dataMomentLabel', { time: m.dataMoment })")
  })

  it('both locales carry the mini-label key', () => {
    const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
    const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')
    expect(zh).toContain('dataMomentLabel')
    expect(en).toContain('dataMomentLabel')
  })
})

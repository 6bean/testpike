/**
 * P5 — time-travel replay ask contract (batch detail → chat dock).
 *
 * The batch detail page carries a per-case "replay" capsule: one click opens
 * the chat dock with the case's latest execution-task pointer installed on the
 * EXISTING badge pointer channel (the server-side replay timeline rides that
 * executionId through the diagnosis segment gate) and one editable question
 * prefilled. The question never auto-sends — the human presses send (same
 * send-door posture as the badge deep link and the palette ask), and no second
 * dock state or second chat surface is created.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import dockRaw from '@/components/ChatDock.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import batchRaw from '@/views/batch/BatchDetailView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import {
  chatDockState,
  closeChatDock,
  consumeReplayAsk,
  requestReplayAsk,
} from '@/composables/useChatDock'

describe('replay ask request (dock singleton)', () => {
  beforeEach(() => {
    closeChatDock()
    consumeReplayAsk()
  })

  it('requesting a replay parks the task pointer and opens the dock', () => {
    requestReplayAsk(4711, '#/batch/12')
    expect(chatDockState.visible).toBe(true)
    expect(chatDockState.openedFrom).toBe('#/batch/12')
    const parked = consumeReplayAsk()
    expect(parked?.taskId).toBe(4711)
  })

  it('consume is one-shot: a second read is empty', () => {
    requestReplayAsk(12)
    expect(consumeReplayAsk()?.taskId).toBe(12)
    expect(consumeReplayAsk()).toBeNull()
  })

  it('a non-positive task id is a no-op (nothing parks, dock stays closed)', () => {
    requestReplayAsk(0)
    expect(chatDockState.visible).toBe(false)
    expect(consumeReplayAsk()).toBeNull()
  })
})

describe('replay capsule wiring (raw source contracts)', () => {
  it('the batch detail routes the capsule through the dock singleton', () => {
    expect(batchRaw).toMatch(/requestReplayAsk/)
    expect(batchRaw).toMatch(/getCaseExecHistory/)
    expect(batchRaw).toMatch(/batch-replay-\$\{row\.caseId\}/)
    // the label rides locale keys only — no hardcoded wording in the component
    expect(batchRaw).toMatch(/t\('batch\.replayAsk'\)/)
  })

  it('a missing replayable run is an honest door, not a silent button', () => {
    expect(batchRaw).toMatch(/t\('batch\.replayAskEmpty'\)/)
  })

  it('the dock consumes the replay request onto the badge pointer channel', () => {
    expect(dockRaw).toMatch(/consumeReplayAsk/)
    // one-shot consumption installs the SAME executionId pointer the diagnosis
    // segment gate already verifies (no second pointer mechanism)
    expect(dockRaw).toMatch(/consumeReplayAsk\(\)\?\.taskId \?\? null/)
    expect(dockRaw).toMatch(/:replay-ask=/)
  })

  it('the panel prefills the replay question without auto-sending', () => {
    expect(panelRaw).toMatch(/replayAsk/)
    // prefill lands on draft (the editable composer model), never on a send call
    expect(panelRaw).toMatch(/draft\.value = replayQuestion\.value/)
  })
})

describe('locale pairing', () => {
  it('both locales carry the capsule label, the honest door and the question', () => {
    expect(zhRaw).toMatch(/replayAsk: '[^']*回放/)
    expect(enRaw).toMatch(/replayAsk: '[^']*Replay/)
    expect(zhRaw).toMatch(/replayAskEmpty: '[^']*没有/)
    expect(enRaw).toMatch(/replayAskEmpty: '[^']*[Nn]o/)
    expect(zhRaw).toMatch(/replayQuestion: '[^']*#\{id\}/)
    expect(enRaw).toMatch(/replayQuestion: '[^']*#\{id\}/)
  })
})

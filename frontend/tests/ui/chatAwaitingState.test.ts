/**
 * segment 1 of the chat-awaiting contract: the answer bubble shows an honest
 * waiting state from the moment a question is sent, and the "empty body"
 * placeholder can never surface while the turn is still running.
 *
 * Pure helpers (chatAwaiting, same discipline as useChatDockPin):
 * - isAwaitingTurn: running + blank content is the awaiting window; first
 * streamed characters (or any terminal state) closes it;
 * - awaitingBand: dwell escalation 15s stage sentence / 60s slow-path note,
 * exported thresholds (one source, no per-page magic numbers);
 * - stageOfProcess: stage word comes from the newest server state frame
 * text when present, else mapped from the last process entry kind
 * (cite=retrieval, plan=planning, reasoning kinds=generation, nothing
 * yet=accepted). No new wire frame kinds are invented here.
 *
 * Wiring assertions are source-as-text (the ?raw precedent of
 * chatDockPin.test.ts):
 * - the answer body branches on isAwaitingTurn BEFORE MarkdownPreview, so
 * an unfinished blank turn renders the typing indicator, never the
 * shared markdown empty placeholder;
 * - the thinking-process section renders while awaiting even with zero
 * journaled entries (immediately visible affordance);
 * - both locale bundles carry the new sentences (parity, no orphan key).
 */
import { describe, expect, it } from 'vitest'
import {
  AWAITING_SLOW_PATH_ESCALATION_MS,
  AWAITING_STAGE_ESCALATION_MS,
  awaitingBand,
  awaitingElapsedSeconds,
  isAwaitingTurn,
  stageOfProcess,
} from '@/composables/chatAwaiting'
import panelRaw from '@/components/ChatPanel.vue?raw'
import zh from '@/utils/locales/zh-CN'
import en from '@/utils/locales/en-US'

describe('awaiting-window detection (pure)', () => {
  it('running + blank content is awaiting; streamed text or any terminal state is not', () => {
    expect(isAwaitingTurn({ state: 'running', content: '' })).toBe(true)
    expect(isAwaitingTurn({ state: 'running', content: '   \n ' })).toBe(true)
    expect(isAwaitingTurn({ state: 'running', content: '你' })).toBe(false)
    expect(isAwaitingTurn({ state: 'sealed', content: '' })).toBe(false)
    expect(isAwaitingTurn({ state: 'failed', content: '' })).toBe(false)
  })
})

describe('dwell escalation bands (pure)', () => {
  it('thresholds are exported single-source numbers matching the agreed dwell times', () => {
    expect(AWAITING_STAGE_ESCALATION_MS).toBe(15_000)
    expect(AWAITING_SLOW_PATH_ESCALATION_MS).toBe(60_000)
  })

  it('typing below the stage threshold, stage sentence after it, slow note at one minute', () => {
    expect(awaitingBand(0)).toBe('typing')
    expect(awaitingBand(AWAITING_STAGE_ESCALATION_MS - 1)).toBe('typing')
    expect(awaitingBand(AWAITING_STAGE_ESCALATION_MS)).toBe('stage')
    expect(awaitingBand(AWAITING_SLOW_PATH_ESCALATION_MS - 1)).toBe('stage')
    expect(awaitingBand(AWAITING_SLOW_PATH_ESCALATION_MS)).toBe('slow')
  })

  it('elapsed seconds floor toward zero, never negative', () => {
    expect(awaitingElapsedSeconds(19_999)).toBe(19)
    expect(awaitingElapsedSeconds(-5)).toBe(0)
  })
})

describe('stage derivation from the process ledger (pure)', () => {
  it('the newest server state frame text wins verbatim (server sentence authority)', () => {
    const entries = [
      { kind: 'state', text: '变量解析' },
      { kind: 'cite', text: 'retrieved' },
      { kind: 'state', text: '上下文组装' },
    ]
    expect(stageOfProcess(entries)).toEqual({ source: 'frame', text: '上下文组装' })
  })

  it('blank state frames fall through to the kind mapping', () => {
    expect(stageOfProcess([{ kind: 'state', text: '  ' }, { kind: 'cite', text: 'x' }])).toEqual({ source: 'key', key: 'retrieval' })
    expect(stageOfProcess([{ kind: 'state', text: '  ' }])).toEqual({ source: 'key', key: 'accepted' })
  })

  it('kind mapping: cite=retrieval, plan=planning, reasoning kinds=generation, empty=accepted', () => {
    expect(stageOfProcess([{ kind: 'cite', text: 'x' }])).toEqual({ source: 'key', key: 'retrieval' })
    expect(stageOfProcess([{ kind: 'plan', text: 'x' }])).toEqual({ source: 'key', key: 'planning' })
    expect(stageOfProcess([{ kind: 'reason', text: 'x' }])).toEqual({ source: 'key', key: 'generation' })
    expect(stageOfProcess([{ kind: 'draft', text: 'x' }])).toEqual({ source: 'key', key: 'generation' })
    expect(stageOfProcess([])).toEqual({ source: 'key', key: 'accepted' })
  })
})

describe('answer-body render gating (wiring)', () => {
  it('the awaiting branch is evaluated before MarkdownPreview so a blank running turn never renders the shared empty placeholder', () => {
    const awaitingIdx = panelRaw.indexOf('isAwaitingTurn(m)')
    const mdIdx = panelRaw.indexOf('<MarkdownPreview v-else')
    expect(awaitingIdx).toBeGreaterThan(-1)
    expect(mdIdx).toBeGreaterThan(awaitingIdx)
    expect(panelRaw).toContain('data-test="chat-awaiting"')
    expect(panelRaw).toContain('data-test="chat-awaiting-stage"')
  })

  it('the thinking-process section stays visible while awaiting with zero entries', () => {
    expect(panelRaw).toContain('m.process.length || isAwaitingTurn(m)')
  })
})

describe('locale parity for the waiting sentences', () => {
  const dock = (bundle: Record<string, any>) => bundle.layout.chatDock
  it('both bundles carry stage sentence, slow note, placeholder line and stage words', () => {
    for (const bundle of [zh, en] as Record<string, any>[]) {
      const d = dock(bundle)
      expect(typeof d.stillProcessing).toBe('string')
      expect(d.stillProcessing).toContain('{n}')
      expect(d.stillProcessing).toContain('{stage}')
      expect(typeof d.slowPathNote).toBe('string')
      expect(typeof d.awaitingLabel).toBe('string')
      for (const key of ['accepted', 'retrieval', 'planning', 'generation']) {
        expect(typeof d.awaitingStage[key]).toBe('string')
      }
    }
  })

  it('zh stage sentence keeps the agreed shape (仍在处理（Ns）·阶段=X)', () => {
    expect(zh.layout.chatDock.stillProcessing).toContain('仍在处理')
  })
})

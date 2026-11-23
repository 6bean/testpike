/**
 * contract spectra (next-step suggestion chips):
 * - the ONE folding point (chatDockReducer) turns the done frame's optional
 * `suggest` member into a chip-shaped side channel [{text, capability}];
 * the server's accepted shape is 1-3 items with non-empty text+capability
 * strings — any drift degrades to an honest empty zone, never a half-shape
 * chip baiting a click and never a broken seal;
 * - a done payload WITHOUT the member (byte-identical legacy shape) folds to
 * an empty array: no suggestion, the seal and the usage numbers behave
 * exactly as before this feature existed;
 * - panel wiring: a chip press hands the question sentence to the composer
 * VERBATIM and sends it through the regular send door (the same posture as
 * answerClarify — proposals never run themselves, the write-chain
 * confirmation gate stays a human click);
 * - visibility: chips belong to the live tail — they show on the newest
 * sealed assistant turn only, and never while a run is in flight;
 * - bilingual locales only (zero CJK in the component source).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  applyFrameToTurn,
  createAssistantTurn,
  parseSuggestions,
  type ChatSuggestion,
} from '@/composables/chatDockReducer'
import panelRaw from '@/components/ChatPanel.vue?raw'

const DONE_BASE = '{"messageId":42,"promptTokens":120,"completionTokens":30}'

function doneFrame(data: string) {
  return { event: 'done', data } as Parameters<typeof applyFrameToTurn>[1]
}

const VALID_SUGGEST = JSON.stringify([
  { text: '把登录用例的断言补齐', capability: 'case.update' },
  { text: '列出本周失败的执行计划', capability: 'aggregate.query' },
])

describe('done-frame suggest fold (single folding point)', () => {
  it('folds a done frame carrying a valid suggest array into turn.suggest', () => {
    const turn = createAssistantTurn(1)
    applyFrameToTurn(turn, doneFrame(`${DONE_BASE.slice(0, -1)},"suggest":${VALID_SUGGEST}}`))
    expect(turn.state).toBe('sealed')
    expect(turn.usage?.promptTokens).toBe(120)
    expect(turn.suggest).toHaveLength(2)
    expect(turn.suggest[0]).toEqual({ text: '把登录用例的断言补齐', capability: 'case.update' })
  })

  it('folds a legacy done payload (no suggest member) to an empty zone — byte-identical guard', () => {
    const turn = createAssistantTurn(2)
    applyFrameToTurn(turn, doneFrame(DONE_BASE))
    expect(turn.state).toBe('sealed')
    expect(turn.messageId).toBe(42)
    expect(turn.suggest).toEqual([])
  })

  it('keeps the seal honest when the done body is unparsable', () => {
    const turn = createAssistantTurn(3)
    applyFrameToTurn(turn, doneFrame('not json'))
    expect(turn.state).toBe('sealed')
    expect(turn.usage).toBeNull()
    expect(turn.suggest).toEqual([])
  })

  it('rejects every drifted shape through the exported parser', () => {
    // Not an array at all.
    expect(parseSuggestions('oops')).toEqual([])
    expect(parseSuggestions({ text: 'a', capability: 'b' })).toEqual([])
    // Empty list / over-cap (the server schema caps the call at 3 items).
    expect(parseSuggestions([])).toEqual([])
    expect(parseSuggestions(
      Array.from({ length: 4 }, (_, i) => ({ text: `问${i}`, capability: 'x' })),
    )).toEqual([])
    // Half-shape items poison the whole array — never a partial chip row.
    expect(parseSuggestions([{ text: 'ok', capability: 'x' }, { text: '  ', capability: 'x' }])).toEqual([])
    expect(parseSuggestions([{ text: 'ok', capability: 'x' }, { capability: 'x' }])).toEqual([])
    expect(parseSuggestions([{ text: 'ok' }])).toEqual([])
    expect(parseSuggestions([{ text: 7, capability: 'x' }])).toEqual([])
    const ok = parseSuggestions(JSON.parse(VALID_SUGGEST)) as ChatSuggestion[]
    expect(ok).toHaveLength(2)
    expect(ok[1].capability).toBe('aggregate.query')
  })
})

describe('panel wiring (a chip press = the user’s own next message, verbatim)', () => {
  it('renders the chip strip between the message list and the composer row', () => {
    const strip = panelRaw.indexOf('data-test="chat-suggest-strip"')
    const composer = panelRaw.indexOf('data-test="chat-dock-input"')
    expect(strip).toBeGreaterThan(-1)
    expect(composer).toBeGreaterThan(strip)
    expect(panelRaw).toContain(':data-test="`chat-suggest-chip-${i}`"')
    expect(panelRaw).toContain('type="button"')
  })

  it('sends the question sentence VERBATIM through the regular door — no second stream channel', () => {
    const door = panelRaw.slice(
      panelRaw.indexOf('function applySuggestion'),
      panelRaw.indexOf('/** Ask one question'),
    )
    expect(door).toContain('draft.value = text')
    expect(door).toContain('void send()')
    // The auto-run trap this pin exists to disprove: a chip never opens the
    // stream by itself, it only hands a message to the human send path.
    expect(door).not.toContain('openChatStream')
  })

  it('shows chips on the newest sealed assistant turn only, never mid-run', () => {
    const gate = panelRaw.slice(
      panelRaw.indexOf('const suggestChips'),
      panelRaw.indexOf('const canSend'),
    )
    expect(gate).toContain('running.value')
    expect(gate).toContain("role === 'assistant'")
    expect(gate).toContain("state === 'sealed'")
    expect(gate).toContain('suggest')
  })

  it('speaks through the bilingual locales only (zero CJK in the panel source)', () => {
    expect(/[\u4e00-\u9fff]/.test(panelRaw)).toBe(false)
    expect(panelRaw).toContain("t('layout.chatDock.suggest.label')")
    for (const locale of ['../../src/utils/locales/zh-CN.ts', '../../src/utils/locales/en-US.ts']) {
      const raw = readFileSync(new URL(locale, import.meta.url), 'utf8')
      const block = raw.slice(raw.indexOf('suggest: {'))
      expect(block.length).toBeGreaterThan(0)
      expect(block.includes('label:')).toBe(true)
    }
  })
})

/**
 * contract spectra (clarification question card):
 * - the ONE folding point (chatDockReducer) turns a journaled clarify frame
 * into a card-shaped side channel {question, options, missingSlot}; an
 * unparsable or drifted meta (the server's accepted shape is 2-4 options)
 * degrades honestly to a process line — never a card-less silent drop,
 * never a half-shape card baiting a click;
 * - the card surface is pinned source-as-text (tests/ui node-environment
 * precedent of chatPlanCard): one click per option, seal-before-emit so a
 * second press can never re-send, every disabled posture states its
 * reason, bilingual locales only (zero CJK in the source);
 * - panel wiring: answering IS the user's own next message through the
 * the write-chain confirmation gate stays a human click.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  applyFrameToTurn,
  createAssistantTurn,
  parseClarifyCard,
  type ClarifyCard,
} from '@/composables/chatDockReducer'
import clarifyRaw from '@/components/ChatClarifyCard.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'

const HUMAN_LINE = '需要你确认一点：要改哪个环境？\n1. 开发环境\n2. 测试环境\n点选一个选项，或直接回复补充说明。'

function clarifyFrame(meta?: string) {
  return { event: 'clarify', data: HUMAN_LINE, meta } as Parameters<typeof applyFrameToTurn>[1]
}

const VALID_META = JSON.stringify({
  question: '要改的是哪个环境的脚本？',
  options: ['开发环境', '测试环境', '预发环境'],
  missingSlot: 'env',
})

describe('clarify frame fold (single folding point)', () => {
  it('folds a clarify frame with parsable meta into turn.clarify', () => {
    const turn = createAssistantTurn(1)
    applyFrameToTurn(turn, clarifyFrame(VALID_META))
    expect(turn.clarify).not.toBeNull()
    expect(turn.clarify?.question).toBe('要改的是哪个环境的脚本？')
    expect(turn.clarify?.options).toHaveLength(3)
    expect(turn.clarify?.missingSlot).toBe('env')
    // The card is a folded side channel, never a duplicate process line.
    expect(turn.process).toHaveLength(0)
  })

  it('degrades an unparsable clarify frame to a plain process line', () => {
    const turn = createAssistantTurn(2)
    applyFrameToTurn(turn, clarifyFrame(undefined))
    expect(turn.clarify).toBeNull()
    expect(turn.process).toEqual([{ kind: 'clarify', text: HUMAN_LINE }])
  })

  it('keeps one card per turn: a later clarify frame replaces the earlier one', () => {
    const turn = createAssistantTurn(3)
    applyFrameToTurn(turn, clarifyFrame(VALID_META))
    applyFrameToTurn(turn, clarifyFrame(JSON.stringify({ question: '换一问', options: ['甲', '乙'] })))
    expect(turn.clarify?.question).toBe('换一问')
    expect(turn.clarify?.options).toEqual(['甲', '乙'])
    // missingSlot is optional on the wire (the server omits the empty one).
    expect(turn.clarify?.missingSlot).toBe('')
  })

  it('rejects drifted shapes through the exported parser (2-4 non-empty options)', () => {
    expect(parseClarifyCard('not json')).toBeNull()
    expect(parseClarifyCard('{"question":"q","options":["只一个"]}')).toBeNull()
    expect(parseClarifyCard('{"question":"q","options":["a","b","c","d","e"]}')).toBeNull()
    expect(parseClarifyCard('{"question":"","options":["a","b"]}')).toBeNull()
    expect(parseClarifyCard('{"question":"q","options":["a",""]}')).toBeNull()
    expect(parseClarifyCard('{"question":"q","options":[1,"b"]}')).toBeNull()
    expect(parseClarifyCard('{"question":"q","options":["a","b"],"missingSlot":7}')).toBeNull()
    const ok = parseClarifyCard(VALID_META) as ClarifyCard
    expect(ok.options).toEqual(['开发环境', '测试环境', '预发环境'])
  })
})

describe('clarify card surface (source-as-text contract)', () => {
  it('renders every option as a real button with a stable test hook', () => {
    expect(clarifyRaw).toContain(':data-test="`chat-clarify-option-${i}`"')
    expect(clarifyRaw).toContain('data-test="chat-clarify-card"')
    expect(clarifyRaw).toContain(':aria-label="t(\'layout.chatDock.clarify.title\')"')
  })

  it('seals the card BEFORE emitting — a second press can never re-send', () => {
    const seal = clarifyRaw.indexOf('answered.value = option')
    const emit = clarifyRaw.indexOf("emit('answer'")
    expect(seal).toBeGreaterThan(-1)
    expect(emit).toBeGreaterThan(seal)
    expect(clarifyRaw).toContain(':disabled="!canAnswer"')
    expect(clarifyRaw).toContain('!props.running && answered.value === \'\'')
  })

  it('states why the doors are shut whenever they are disabled', () => {
    expect(clarifyRaw).toContain(':title="disabledReason || undefined"')
    expect(clarifyRaw).toContain("t('layout.chatDock.clarify.disabledSealed'")
    expect(clarifyRaw).toContain("t('layout.chatDock.clarify.disabledRunning')")
    // Vision precedent (plan card): a disabled control swaps to a neutral
    // fill, never an opacity wash that still scans as clickable.
    const disabledRule = clarifyRaw.slice(clarifyRaw.indexOf('.chat-clarify-option:disabled'))
    expect(disabledRule.slice(0, disabledRule.indexOf('}'))).toContain('--el-fill-color-dark')
    expect(disabledRule.slice(0, disabledRule.indexOf('}'))).not.toContain('opacity')
  })

  it('speaks through the bilingual locales only (zero CJK in the source)', () => {
    expect(/[\u4e00-\u9fff]/.test(clarifyRaw)).toBe(false)
    for (const locale of ['../../src/utils/locales/zh-CN.ts', '../../src/utils/locales/en-US.ts']) {
      const raw = readFileSync(new URL(locale, import.meta.url), 'utf8')
      expect(raw).toContain('clarify: {')
      const block = raw.slice(raw.indexOf('clarify: {'))
      for (const key of ['disabledRunning', 'disabledSealed', 'answerMessage']) {
        expect(block.includes(`${key}:`)).toBe(true)
      }
    }
  })
})

describe('panel wiring (an answer = the user’s own next message)', () => {
  it('renders the card off the folded clarify and routes answers to answerClarify', () => {
    expect(panelRaw).toContain("import ChatClarifyCard from '@/components/ChatClarifyCard.vue'")
    expect(panelRaw).toContain('<ChatClarifyCard v-if="m.clarify" :card="m.clarify" :running="running" @answer="answerClarify" />')
  })

  it('composes the answer message and sends through the regular door — no second stream channel', () => {
    const door = panelRaw.slice(panelRaw.indexOf('function answerClarify'), panelRaw.indexOf('/** Ask one question'))
    expect(door).toContain("draft.value = t('layout.chatDock.clarify.answerMessage'")
    expect(door).toContain('void send()')
    // The auto-continue trap this pin exists to disprove: the clarify door
    // must never open the stream by itself, only hand a message to the human
    // send path (same posture as the plan card's confirm).
    expect(door).not.toContain('openChatStream')
  })
})

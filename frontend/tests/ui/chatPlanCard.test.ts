/**
 * package 3 contract spectra (multi-step plan card):
 * - the ONE folding point (chatDockReducer) turns a journaled plan frame
 * into a card-shaped side channel; an unparsable meta degrades honestly
 * to a process line instead of vanishing;
 * - the card surface itself is pinned source-as-text (the tests/ui node
 * environment precedent of chatDockWiring/chatFabMount): tick + reorder +
 * single-fire confirm seal, full i18n, label association; the rendered
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  applyFrameToTurn,
  createAssistantTurn,
  parsePlanCard,
  type PlanCard,
} from '@/composables/chatDockReducer'
import planRaw from '@/components/ChatPlanCard.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'

function planFrame(meta?: string) {
  return { event: 'plan', data: '多步执行计划（共 3 步，确认后开始执行）', meta } as Parameters<typeof applyFrameToTurn>[1]
}

const VALID_META = JSON.stringify({
  steps: [
    { index: 1, title: '拉取待处理缺陷' },
    { index: 2, title: '生成回归用例' },
    { index: 3, title: '提交评审' },
  ],
})

describe('plan frame fold (single folding point)', () => {
  it('folds a plan frame with parsable steps into turn.plan', () => {
    const turn = createAssistantTurn(1)
    applyFrameToTurn(turn, planFrame(VALID_META))
    expect(turn.plan).not.toBeNull()
    expect(turn.plan?.steps).toHaveLength(3)
    expect(turn.plan?.steps[1].title).toBe('生成回归用例')
    // The card is a folded side channel, never a duplicate process line.
    expect(turn.process).toHaveLength(0)
  })

  it('degrades an unparsable plan frame to a plain process line', () => {
    const turn = createAssistantTurn(2)
    applyFrameToTurn(turn, planFrame(undefined))
    expect(turn.plan).toBeNull()
    expect(turn.process).toEqual([{ kind: 'plan', text: '多步执行计划（共 3 步，确认后开始执行）' }])
  })

  it('rejects empty or misshapen step lists through the exported parser', () => {
    expect(parsePlanCard('{"steps":[]}')).toBeNull()
    expect(parsePlanCard('{"steps":[{"title":"no index"}]}')).toBeNull()
    expect(parsePlanCard('not json')).toBeNull()
    const ok = parsePlanCard(VALID_META) as PlanCard
    expect(ok.steps.map((s) => s.index)).toEqual([1, 2, 3])
  })
})

describe('plan card surface (source-as-text contract)', () => {
  it('every step carries a real label association and both move doors', () => {
    expect(planRaw).toContain(':id="`chat-plan-ck-${r.key}`"')
    expect(planRaw).toContain(':for="`chat-plan-ck-${r.key}`"')
    expect(planRaw).toContain(':data-test="`chat-plan-up-${i}`"')
    expect(planRaw).toContain(':data-test="`chat-plan-down-${i}`"')
  })

  it('seals the card BEFORE emitting — a second press can never re-fire', () => {
    const seal = planRaw.indexOf('confirmed.value = true')
    const emit = planRaw.indexOf("emit('confirm'")
    expect(seal).toBeGreaterThan(-1)
    expect(emit).toBeGreaterThan(seal)
    expect(planRaw).toContain(':disabled="!canConfirm"')
    expect(planRaw).toContain('!props.running && !confirmed.value')
  })

  it('states why the confirm door is shut whenever it is disabled', () => {
    // Vision round (plan card faces): a disabled button with no reason reads
    // as broken; every disabled posture carries an honest one-line pointer.
    expect(planRaw).toContain(':title="disabledReason || undefined"')
    expect(planRaw).toContain("t('layout.chatDock.plan.disabledSealed')")
    expect(planRaw).toContain("t('layout.chatDock.plan.disabledRunning')")
    expect(planRaw).toContain("t('layout.chatDock.plan.disabledEmpty')")
    // Vision rounds 1+2: an opacity-washed blue button still SCANS as bright
    // blue and clickable; the disabled posture swaps to a neutral fill.
    const disabledRule = planRaw.slice(planRaw.indexOf('.chat-plan-confirm:disabled'))
    expect(disabledRule.slice(0, disabledRule.indexOf('}'))).toContain('--el-fill-color-dark')
    expect(disabledRule.slice(0, disabledRule.indexOf('}'))).not.toContain('opacity')
    for (const locale of ['../../src/utils/locales/zh-CN.ts', '../../src/utils/locales/en-US.ts']) {
      const raw = readFileSync(new URL(locale, import.meta.url), 'utf8')
      for (const key of ['disabledRunning', 'disabledEmpty', 'disabledSealed']) {
        expect(raw).toContain(`${key}:`)
      }
    }
  })

  it('speaks through the bilingual locales only (zero CJK in the source)', () => {
    expect(/[\u4e00-\u9fff]/.test(planRaw)).toBe(false)
  })
})

describe('panel wiring (confirm = the user’s own next question)', () => {
  it('renders the card off the folded plan and routes confirm to startPlanRun', () => {
    expect(panelRaw).toContain('import ChatPlanCard from \'@/components/ChatPlanCard.vue\'')
    expect(panelRaw).toContain('<ChatPlanCard v-if="m.plan" :plan="m.plan" :running="running" @confirm="startPlanRun" />')
  })

  it('composes the run message and sends through the regular door — no second stream channel', () => {
    const door = panelRaw.slice(panelRaw.indexOf('function startPlanRun'), panelRaw.indexOf('/** Ask one question'))
    expect(door).toContain('draft.value = t(\'layout.chatDock.plan.runMessage\'')
    expect(door).toContain('void send()')
    // The auto-run trap this pin exists to disprove: the plan door must never
    // open the stream by itself, only hand a question to the human send path.
    expect(door).not.toContain('openChatStream')
  })
})

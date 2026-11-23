/**
 * Goal-card layout contract .
 *
 * Pinned source-as-text (the tests/ui node-environment precedent of
 * chatPlanCard/chatDockWiring — the rendered behaviour is covered by the
 * goal E2E spectrum): the card is the session's persistent state surface,
 * so the header alone must answer "how far / doing what / what broke"
 * with the detail folded by default, and the duplicated bottom summary
 * paragraph (R23 shell row) is gone for good. The wire vocabulary stays
 * a stored state.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import cardRaw from '@/components/ChatGoalCard.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'

const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')

describe('goal card header carries the whole signal folded (T532)', () => {
  it('detail is folded by default', () => {
    expect(cardRaw).toContain('const collapsed = ref(true)')
  })

  it('the header row owns count, badges and the current step', () => {
    expect(cardRaw).toContain('data-test="chat-goal-count"')
    expect(cardRaw).toContain('data-test="chat-goal-badge-pending"')
    expect(cardRaw).toContain('data-test="chat-goal-badge-blocked"')
    expect(cardRaw).toContain('data-test="chat-goal-current"')
    // Badges render only when their count is above zero — a silent 0/0
    // noise pair on a clean ledger would re-add the header clutter
    // set out to remove.
    expect(cardRaw).toContain('v-if="pendingCount > 0"')
    expect(cardRaw).toContain('v-if="blockedCount > 0"')
  })

  it('the progress strip is a real progressbar with spoken values', () => {
    expect(cardRaw).toContain('role="progressbar"')
    expect(cardRaw).toContain(':aria-valuenow="doneCount"')
    expect(cardRaw).toContain('aria-valuemin="0"')
    expect(cardRaw).toContain(':aria-valuemax="total"')
    expect(cardRaw).toContain('data-test="chat-goal-progress"')
  })

  it('the duplicated bottom summary paragraph is gone (R23)', () => {
    expect(cardRaw).not.toContain('chat-goal-sentence')
    expect(cardRaw).not.toContain('goal.sentence')
    // The reducer keeps folding the sentence as the reload door; the card
    // simply never renders it (chatGoalProgressFold still pins the field).
    expect(cardRaw).toContain('const steps = computed(() => props.goal.steps)')
  })

  it('running is a display overlay on the first open row, never fake', () => {
    expect(cardRaw).toContain('defineProps<{ goal: GoalProgress; running: boolean }>')
    // blocked row keeps its failure wording even while the run streams.
    expect(cardRaw).toContain(
      "if (props.running && state === 'pending' && index === currentIndex.value) {",
    )
  })

  it('worst-scale containment survives the rebuild (50-step ledger)', () => {
    expect(cardRaw).toContain('max-height: min(50vh, 480px)')
    expect(cardRaw).toContain('overflow-y: auto')
  })
})

/**
 * Remediation round after the vision scoring : the header run of
 * count + chips + chevron read as one undivided line, the detail block had
 * no separation from the head, and the card crowded the composer. Each rule
 * below is the machine anchor for one of those findings.
 */
describe('goal card header groups and rhythm (T532 remediation round)', () => {
  it('state badges are one group behind a divider that never floats alone', () => {
    expect(cardRaw).toContain('class="chat-goal-badges"')
    expect(cardRaw).toContain('v-if="pendingCount > 0 || blockedCount > 0"')
    expect(cardRaw).toContain('border-left: 1px solid var(--el-border-color-lighter)')
  })

  it('the head row is optically centred and stays a comfortable target', () => {
    expect(cardRaw).toMatch(/\.chat-goal-toggle \{[\s\S]*?align-items: center;[\s\S]*?min-height: 24px;/)
  })

  it('head and detail blocks are separated by a divider plus padding', () => {
    expect(cardRaw).toMatch(/\.chat-goal-steps \{[\s\S]*?padding: 8px 0 0;[\s\S]*?border-top: 1px solid var\(--el-border-color-lighter\);/)
  })

  it('outer gap comes from the theme band-gap token, not a page number', () => {
    expect(cardRaw).toContain('margin: var(--tp-band-gap, 16px) 0')
  })
})

describe('goal card mount and vocabulary', () => {
  it('only the newest open tail owns one card per thread (R33)', () => {
    expect(panelRaw).toContain('v-if="m.goal && mi === lastGoalIndex"')
    expect(panelRaw).toContain(':running="running"')
    expect(panelRaw).toContain('const lastGoalIndex = computed(() => {')
  })

  it('both locales speak the five-state chips, blocked renamed to failure', () => {
    for (const loc of [zh, en]) {
      expect(loc).toContain('running: ')
      expect(loc).toContain('current: ')
      expect(loc).toContain('badgePending: ')
      expect(loc).toContain('badgeBlocked: ')
    }
    expect(zh).toContain("state: { pending: '待执行', running: '进行中', done: '已完成', skipped: '已跳过', blocked: '失败' }")
    expect(en).toContain("state: { pending: 'To do', running: 'In progress', done: 'Done', skipped: 'Skipped', blocked: 'Failed' }")
    // Old wording must not survive anywhere in the goal block.
    expect(zh).not.toContain("blocked: '受阻'")
    expect(en).not.toContain("blocked: 'Blocked'")
  })
})

/**
 * Cost-transparency line . The ledger card already
 * counts steps; this round the header answers the money question too: how
 * many tokens the session has already spent (the figure the server's cost
 * tail folded — never re-computed here) and how many steps still remain.
 * When the consumption door could not answer (consumedTokens null) the cost
 * chip is honestly absent — a card must never paint a fabricated zero.
 */
describe('goal card cost line (T527 item 5)', () => {
  it('the cost chip renders only when the folded figure exists', () => {
    expect(cardRaw).toContain('data-test="chat-goal-cost"')
    expect(cardRaw).toContain('v-if="goal.consumedTokens !== null"')
    expect(cardRaw).toContain("t('layout.chatDock.goal.cost', { tokens: goal.consumedTokens })")
    // Remaining steps ride the SAME openCount the fold pinned — one source,
    // the card never re-parses the sentence.
    expect(cardRaw).toContain("t('layout.chatDock.goal.remaining', { n: goal.openCount })")
  })

  it('both locales name the cost line in operational wording (W6)', () => {
    expect(zh).toContain("cost: '已消耗 {tokens} tokens'")
    expect(zh).toContain("remaining: '剩余 {n} 步'")
    expect(en).toContain("cost: '{tokens} tokens used'")
    expect(en).toContain("remaining: '{n} steps left'")
  })
})

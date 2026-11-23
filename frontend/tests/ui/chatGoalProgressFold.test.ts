/**
 * Goal-progress fold contract .
 *
 * The orchestrator journals exactly two machine lines for a goal-plan ledger:
 * the plan frame (ordered steps, meta carried) and — when the turn finishes
 * while the ledger still owes work — one state frame BEFORE the done frame
 * ChatFrameType stays at its nine kinds (red line: zero new frame types), so
 * the front door recognises the open-tail sentence by its prefix and folds it
 * into a collapsible progress card instead of a bare process line.
 *
 * Pinned semantics (one folding point shared by live stream and reconnect
 * replay, same posture as cite/draft/plan):
 * - a plan frame seeds the card: every step pending;
 * - the open-tail state frame marks the steps whose titles appear inside
 * its parentheses pending, the rest done — substring matching, because a
 * step title may itself contain the enumeration comma;
 * - an open title the plan frame never listed still rides the card
 * (pending): the card never silently drops a step the ledger names;
 * - when a card was folded the sentence is the card's, NOT a duplicate
 * process line; without a plan frame (or with a drifted sentence) the
 * state frame keeps its honest process-line posture untouched;
 * - replace-on-fold: the newest open-tail frame wins, one card per turn.
 */
import { describe, expect, it } from 'vitest'
import { mapHistoryToTurns, type ChatMessageRowView } from '@/composables/chatHistoryMapper'
import {
  applyFrameToTurn,
  createAssistantTurn,
  parseGoalOpenTail,
  type DockTurn,
} from '@/composables/chatDockReducer'
import type { SseFrame } from '@/api/sseFrameParser'

const PLAN_META = JSON.stringify({
  steps: [
    { index: 1, title: '收集需求' },
    { index: 2, title: '生成、评审用例' },
    { index: 3, title: '汇总报告' },
  ],
})

function planFrame(): SseFrame {
  return { event: 'plan', data: '多步执行计划（共 3 步，确认后开始执行）', meta: PLAN_META } as SseFrame
}

function openTailFrame(titles: string[], n?: number): SseFrame {
  const count = n ?? titles.length
  return {
    event: 'state',
    data: `目标计划还有 ${count} 步未完成（${titles.join('、')}），自动接续第 1 批。`,
  } as SseFrame
}

function withPlan(): DockTurn {
  const turn = createAssistantTurn(1)
  applyFrameToTurn(turn, planFrame())
  return turn
}

describe('goal-progress fold (T522 slice 3)', () => {
  it('a plan frame alone stays the T453 confirm door: no progress card yet', () => {
    // proposed a plan shows the tickable launch card — rendering a second
    // step list pretending progress would bait the eye with a fake ledger.
    const turn = withPlan()
    expect(turn.plan).not.toBeNull()
    expect(turn.goal).toBeNull()
  })

  it('open-tail marks named steps pending and the rest done, no duplicate process line', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, openTailFrame(['汇总报告']))
    expect(turn.goal!.steps).toEqual([
      { title: '收集需求', state: 'done' },
      { title: '生成、评审用例', state: 'done' },
      { title: '汇总报告', state: 'pending' },
    ])
    expect(turn.goal!.openCount).toBe(1)
    expect(turn.process.find((p) => p.kind === 'state')).toBeUndefined()
  })

  it('matches the enumeration comma inside a step title (substring rule)', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, openTailFrame(['生成、评审用例']))
    expect(turn.goal!.steps.find((s) => s.title === '生成、评审用例')!.state).toBe('pending')
    expect(turn.goal!.steps.find((s) => s.title === '收集需求')!.state).toBe('done')
  })

  it('an open title the plan frame never listed still rides the card as pending', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, openTailFrame(['汇总报告', '补充回归夹具']))
    const titles = turn.goal!.steps.map((s) => s.title)
    expect(titles).toContain('补充回归夹具')
    expect(turn.goal!.steps.find((s) => s.title === '补充回归夹具')!.state).toBe('pending')
  })

  // root cause (slot3 live probe): the ledger joins open titles with
  // yields fragments of a comma-bearing title. Those fragments used to be
  // appended as brand-new pending rows — the card inflated (5 open steps
  // rendered 7~8 rows) and every phantom row was pending, burying the done
  // steps (done stuck at 0). Pinned: a fragment contained in an existing row
  // IS that step — one row per open step, both folding arms.
  it('a comma-bearing open title never inflates the card with fragment rows', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, openTailFrame(['生成、评审用例', '汇总报告']))
    expect(turn.goal!.steps.map((s) => s.title)).toEqual(['收集需求', '生成、评审用例', '汇总报告'])
    expect(turn.goal!.steps.filter((s) => s.state === 'done').map((s) => s.title)).toEqual(['收集需求'])
    expect(turn.goal!.openCount).toBe(2)
  })

  it('plain titles keep the exact fold: planned rows plus one appended never-planned step', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, openTailFrame(['收集需求', '补充回归夹具']))
    expect(turn.goal!.steps.map((s) => s.title)).toEqual([
      '收集需求',
      '生成、评审用例',
      '汇总报告',
      '补充回归夹具',
    ])
    expect(turn.goal!.steps.find((s) => s.title === '生成、评审用例')!.state).toBe('done')
  })

  it('replace-on-fold: the newest open-tail frame wins', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, openTailFrame(['收集需求', '生成、评审用例', '汇总报告']))
    applyFrameToTurn(turn, openTailFrame(['汇总报告']))
    expect(turn.goal!.openCount).toBe(1)
    expect(turn.goal!.steps.filter((s) => s.state === 'pending').length).toBe(1)
  })

  it('without a plan frame the open-tail keeps its honest process-line posture', () => {
    const turn = createAssistantTurn(1)
    const frame = openTailFrame(['甲'])
    applyFrameToTurn(turn, frame)
    expect(turn.goal).toBeNull()
    expect(turn.process).toEqual([{ kind: 'state', text: frame.data }])
  })

  // vision remediation: the auto-batch open-tail line must never dump
  // the whole ledger onto the process area — 40 named steps rendered as one
  // wall of text read like a system log. The line clips to the first three
  // step names plus an explicit ellipsis; the trailing clause (which batch /
  // cap stop + resume hint) stays verbatim. Short enumerations stay verbatim
  // too — clipping only kicks in beyond three names.
  it('an un-planed open-tail clips its enumeration to the first three names', () => {
    const turn = createAssistantTurn(1)
    const titles = Array.from({ length: 40 }, (_, i) => `目标残步${String(i + 1).padStart(3, '0')}`)
    applyFrameToTurn(turn, openTailFrame(titles))
    expect(turn.goal).toBeNull()
    const line = turn.process[0].text
    expect(line).toContain('目标残步001、目标残步002、目标残步003…')
    expect(line).not.toContain('目标残步004')
    expect(line).toContain('还有 40 步未完成')
    expect(line.endsWith('，自动接续第 1 批。')).toBe(true)
  })

  it('a short open-tail enumeration stays verbatim on the process line', () => {
    const turn = createAssistantTurn(1)
    const frame = openTailFrame(['甲', '乙', '丙'])
    applyFrameToTurn(turn, frame)
    expect(turn.process).toEqual([{ kind: 'state', text: frame.data }])
  })

  it('a drifted or non-ledger state sentence is never swallowed', () => {
    const turn = withPlan()
    const noisy = { event: 'state', data: '正在检索知识库…' } as SseFrame
    applyFrameToTurn(turn, noisy)
    expect(turn.process).toEqual([{ kind: 'state', text: '正在检索知识库…' }])
    expect(parseGoalOpenTail(noisy.data)).toBeNull()
  })

  it('parseGoalOpenTail answers null for malformed tails, numbers for honest ones', () => {
    expect(parseGoalOpenTail('目标计划还有 步未完成（甲）。')).toBeNull()
    expect(parseGoalOpenTail('目标计划还有 2 步未完成缺括号')).toBeNull()
    const tail = parseGoalOpenTail('目标计划还有 2 步未完成（甲、乙），自动接续第 1 批。')
    expect(tail).not.toBeNull()
    expect(tail!.openCount).toBe(2)
    expect(tail!.openText).toBe('甲、乙')
  })
})

/**
 * Reload fold : sealed history rows never replay the plan or
 * state frames through the messages door, so the server projects the
 * session's still-open ledger tail onto the newest assistant row. The mapper
 * folds that projection through the SAME live parsers — the card survives a
 * refresh with the stream's semantics, and a drifted or absent projection
 * never fabricates one.
 */
function sealedRow(overrides: Partial<ChatMessageRowView>): ChatMessageRowView {
  return {
    id: 11,
    sessionId: 3,
    role: 'assistant',
    content: '先做第一步',
    reasoning: null,
    status: 'done',
    seqFrom: null,
    seqTo: null,
    promptTokens: 0,
    completionTokens: 0,
    ...overrides,
  } as ChatMessageRowView
}

describe('goal-progress reload fold (T522 slice 3b)', () => {
  it('the projected open tail folds into the card through the live parsers', () => {
    const row = sealedRow({
      goalPlan: {
        planVersion: 1,
        revision: 2,
        openCount: 2,
        openTitles: ['生成、评审用例', '汇总报告'],
        openTailSentence: '目标计划还有 2 步未完成（生成、评审用例、汇总报告），自动接续第 1 批。',
      },
    })
    const turns = mapHistoryToTurns([row], 0)
    const goal = turns[0].goal
    expect(goal).not.toBeNull()
    expect(goal!.openCount).toBe(2)
    expect(goal!.sentence).toBe(row.goalPlan!.openTailSentence)
    // The live fold is the one semantics: titles seed in ledger order and
    // a comma-bearing title folds to exactly one row — the append loop's
    // fragment guard (root cause) keeps the reload arm equal to the
    // stream arm, fragments of a listed title never ride as phantom steps.
    expect(goal!.steps.slice(0, 2).map((s) => s.title)).toEqual(['生成、评审用例', '汇总报告'])
    expect(goal!.steps.find((s) => s.title === '生成、评审用例')!.state).toBe('pending')
    expect(goal!.steps.every((s) => s.state === 'pending')).toBe(true)
  })

  it('a reload projection folds one row per open step — comma fragments never appended', () => {
    const row = sealedRow({
      goalPlan: {
        planVersion: 1,
        revision: 2,
        openCount: 2,
        openTitles: ['生成、评审用例', '汇总报告'],
        openTailSentence: '目标计划还有 2 步未完成（生成、评审用例、汇总报告），自动接续第 1 批。',
      },
    })
    const goal = mapHistoryToTurns([row], 0)[0].goal
    expect(goal).not.toBeNull()
    expect(goal!.steps.map((s) => s.title)).toEqual(['生成、评审用例', '汇总报告'])
    expect(goal!.openCount).toBe(2)
  })

  it('a drifted server sentence folds to no card and never throws', () => {
    const row = sealedRow({
      goalPlan: {
        planVersion: 1,
        revision: 1,
        openCount: 1,
        openTitles: ['甲'],
        openTailSentence: '目标计划还有 1 步未完成，请回复继续。',
      },
    })
    expect(mapHistoryToTurns([row], 0)[0].goal).toBeNull()
  })

  it('rows without a projection keep an honest empty goal zone', () => {
    expect(mapHistoryToTurns([sealedRow({})], 0)[0].goal).toBeNull()
  })

  it('the fold rides only the row carrying the projection', () => {
    const open = {
      planVersion: 1,
      revision: 1,
      openCount: 1,
      openTitles: ['甲'],
      openTailSentence: '目标计划还有 1 步未完成（甲）。回复「继续」即可接着执行。',
    }
    const rows = [
      sealedRow({ id: 10, role: 'user', content: '帮我分步做', goalPlan: null }),
      sealedRow({ id: 11, goalPlan: open }),
    ]
    const turns = mapHistoryToTurns(rows, 0)
    expect(turns[0].goal).toBeNull()
    expect(turns[1].goal).not.toBeNull()
  })

  // Residual face (DOM re-check): the ledger stores per-step state
  // (pending/blocked), but the reload fold flattened every open step to
  // pending via the sentence-substring rule — a blocked step reappeared as
  // pending after a refresh, silently losing the blocked signal the ledger
  // actually holds. The projection therefore carries each open title's own
  // state; the fold honours it while the sentence stays the card door.
  it('a blocked step in the projected ledger renders 受阻 not 待执行', () => {
    const row = sealedRow({
      goalPlan: {
        planVersion: 1,
        revision: 3,
        openCount: 2,
        openTitles: ['生成用例', '评审确认'],
        openStates: ['pending', 'blocked'],
        openTailSentence: '目标计划还有 2 步未完成（生成用例、评审确认）。回复「继续」即可接着执行。',
      },
    })
    const goal = mapHistoryToTurns([row], 0)[0].goal
    expect(goal).not.toBeNull()
    expect(goal!.steps.find((s) => s.title === '生成用例')!.state).toBe('pending')
    expect(goal!.steps.find((s) => s.title === '评审确认')!.state).toBe('blocked')
  })

  it('a projection without per-step states keeps every open row pending (old server)', () => {
    const row = sealedRow({
      goalPlan: {
        planVersion: 1,
        revision: 1,
        openCount: 1,
        openTitles: ['甲'],
        openTailSentence: '目标计划还有 1 步未完成（甲）。回复「继续」即可接着执行。',
      },
    })
    const goal = mapHistoryToTurns([row], 0)[0].goal
    expect(goal!.steps.every((s) => s.state === 'pending')).toBe(true)
  })

  it('an unknown projected state value degrades to pending, never a fake glyph', () => {
    const row = sealedRow({
      goalPlan: {
        planVersion: 1,
        revision: 1,
        openCount: 1,
        openTitles: ['甲'],
        openStates: ['stairway'],
        openTailSentence: '目标计划还有 1 步未完成（甲）。回复「继续」即可接着执行。',
      },
    })
    const goal = mapHistoryToTurns([row], 0)[0].goal
    expect(goal!.steps.find((s) => s.title === '甲')!.state).toBe('pending')
  })
})

/**
 * Cost-transparency fold .
 *
 * The orchestrator's GoalCostLine.appendCostTail rides the SAME open-tail
 * state frame: the known tail names the session's consumed tokens, the
 * honest unknown tail says the figure could not be read this turn (both
 * wordings pinned by the server-side GoalCostLine tests). The figure is
 * the session ledger the
 * budget gate and the E9 cost view read (CoreGateway.sessionTotalTokens) —
 * one number, one source, front end never re-computes it. Folding happens
 * at the ONE point (foldGoalProgress), so live stream, reconnect replay and
 * the reload projection all land the same shape.
 *
 * Pinned semantics:
 * - a pinned known cost tail folds consumedTokens as the parsed integer;
 * - the honest unknown tail folds null (never a fabricated 0 — a cost
 * number that lies is worse than no cost number);
 * - a sentence with no cost tail (old server, drifted wording) folds null
 * and still folds the card exactly as before — the pinned open-tail
 * prefix anchor is untouched by the appended tail.
 */
describe('goal-progress cost fold (T527 item 5)', () => {
  it('a live open-tail frame with a known cost tail folds the consumed figure', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, {
      event: 'state',
      data: '目标计划还有 2 步未完成（生成、评审用例、汇总报告），自动接续第 1 批。；本会话已消耗 1234 tokens。',
    } as SseFrame)
    expect(turn.goal).not.toBeNull()
    expect(turn.goal!.consumedTokens).toBe(1234)
    // The regression anchor: the pinned prefix fold still works with the tail on.
    expect(turn.goal!.openCount).toBe(2)
  })

  it('the honest unknown cost tail folds null, never a fabricated zero', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, {
      event: 'state',
      data: '目标计划还有 1 步未完成（甲），自动接续第 2 批。；本会话消耗量本次未能读取。',
    } as SseFrame)
    expect(turn.goal).not.toBeNull()
    expect(turn.goal!.consumedTokens).toBeNull()
  })

  it('a sentence without any cost tail folds null and the card is unchanged', () => {
    const turn = withPlan()
    applyFrameToTurn(turn, openTailFrame(['生成用例', '汇总报告']))
    expect(turn.goal).not.toBeNull()
    expect(turn.goal!.consumedTokens).toBeNull()
    expect(turn.goal!.openCount).toBe(2)
  })

  it('the reload projection folds the cost figure through the same parser', () => {
    const row = sealedRow({
      goalPlan: {
        planVersion: 2,
        revision: 1,
        openCount: 2,
        openTitles: ['生成用例', '评审确认'],
        openTailSentence: '目标计划还有 2 步未完成（生成用例、评审确认），自动接续第 1 批。；本会话已消耗 987 tokens。',
      },
    })
    const goal = mapHistoryToTurns([row], 0)[0].goal
    expect(goal).not.toBeNull()
    expect(goal!.consumedTokens).toBe(987)
  })
})

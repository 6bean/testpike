/**
 * segment c3 survey-tail contract (R62 process visibility for the
 * "survey this page" door): the ledger page must replay the survey tail
 * through the SHARED read door and the SHARED tail base — one cursor
 * channel, auto-open on dispatch, auto-collapse plus a silent list reload
 * on the terminal frame. Guards here pin the three behaviours the redesign
 * promised: cursor replay, dispatch trigger, terminal collapse.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { isSurveyTerminalRow } from '@/api/webElements'
import { useTestRunTail, type TestRunEvent } from '@/composables/useTestRunTail'

const here = dirname(fileURLToPath(import.meta.url))

// R62 value-face sentence family, single source : manual
// refresh / reopen-later instructions are banned on every user-visible ack,
// locale values and backend event frames alike. Shared by the locale sweep
// and the backend pump sweep so the two faces can never drift apart.
const ZH_REFRESH_INSTRUCTION =
  /(直接刷新|请刷新|刷新后重试|刷新页面|稍后刷新|稍后再刷新|手动刷新|刷新后的列表|稍后重开|重开查看|点击「刷新」|点击『刷新』)/
const EN_REFRESH_INSTRUCTION =
  /( or refresh\b|refresh and |later for refresh|refresh later|reopen [a-z ]*later|refresh the page|click (the )?'?refresh\b|press refresh)/i

function event(cursor: number, summary: string, level = 'step'): TestRunEvent {
  return { cursor, level, summary, detail: null, landedAt: `2026-11-18T08:0${cursor}:00` }
}

describe('isSurveyTerminalRow：服务端终态帧四前缀单源命中', () => {
  it('完成/预算暂停/提前结束/操作人停止都算终态', () => {
    expect(isSurveyTerminalRow(event(1, '测绘完成｜候选 12 确认 3｜步 9 页 6'))).toBe(true)
    expect(isSurveyTerminalRow(event(2, '预算耗尽自动暂停（非失败）｜候选 4 确认 0｜步 20 页 12'))).toBe(true)
    expect(isSurveyTerminalRow(event(3, '同页连续无进展提前结束｜候选 1 确认 0｜步 5 页 1'))).toBe(true)
    expect(isSurveyTerminalRow(event(4, '测绘会话已停止（操作人取消）｜候选 2 确认 1'))).toBe(true)
  })

  it('步骤帧与命名帧不是终态（尾窗不得中途收档）', () => {
    expect(isSurveyTerminalRow(event(5, '步骤 3｜页面 http://a｜候选 8｜入账 CREATE=2'))).toBe(false)
    expect(isSurveyTerminalRow(event(6, '命名 5 枚候选已入账'))).toBe(false)
  })
})

describe('useTestRunTail 异步尾窗模式（option 驱动，游标回放单通道）', () => {
  it('空批次在宽限轮内继续拉取：首帧未落的测绘早期绝不提前收轮', async () => {
    const batches: TestRunEvent[][] = [[], [], [], [], [], [event(1, '步骤 1｜开始')], []]
    let call = 0
    const fetchEvents = vi.fn(async () => {
      const batch = batches[Math.min(call, batches.length - 1)]
      call += 1
      return call >= batches.length + 2
        ? [event(2, '测绘完成｜候选 1 确认 0｜步 1 页 1')]
        : batch
    })
    const tail = useTestRunTail(fetchEvents, {
      terminal: isSurveyTerminalRow,
      emptyGracePolls: 30,
      pollIntervalMs: 1,
    })
    await tail.openRun({ runToken: '91', verdict: '', verdictHuman: '' })
    // legacy default (4 empty polls) would have broken at call 5; async mode
    // kept the single cursor channel alive past the empty window.
    expect(fetchEvents.mock.calls.length).toBeGreaterThan(5)
    expect(tail.events.value.map((r) => r.cursor)).toEqual([1, 2])
    expect(tail.running.value).toBe(false)
  })

  it('终态帧命中即停轮：其后零请求，closeOnTerminal 自动收档', async () => {
    const fetchEvents = vi.fn(async (_token: string, cursor: number) => {
      if (cursor === 0) return [event(1, '步骤 1｜开始'), event(2, '步骤 2｜命名')]
      if (cursor === 2) return [event(3, '测绘完成｜候选 6 确认 0｜步 2 页 2')]
      return [event(4, '步骤 4｜不应被拉到的后续')]
    })
    const tail = useTestRunTail(fetchEvents, {
      terminal: isSurveyTerminalRow,
      pollIntervalMs: 1,
      closeOnTerminal: true,
    })
    tail.openRun({ runToken: '77', verdict: '', verdictHuman: '' })
    expect(tail.drawerOpen.value).toBe(true)
    await vi.waitFor(() => expect(tail.running.value).toBe(false))
    expect(tail.drawerOpen.value).toBe(false)
    expect(tail.events.value.map((r) => r.cursor)).toEqual([1, 2, 3])
    const callsAfter = fetchEvents.mock.calls.length
    await new Promise((r) => setTimeout(r, 10))
    expect(fetchEvents.mock.calls.length).toBe(callsAfter)
  })

  it('缺省同步门形态零漂移：空满 4 轮即断，旧「结束」文本仍判终态', async () => {
    let call = 0
    const fetchEvents = vi.fn(async () => {
      call += 1
      return []
    })
    const tail = useTestRunTail(fetchEvents, { pollIntervalMs: 1 })
    await tail.openRun({ runToken: '5', verdict: 'ok', verdictHuman: '通过' })
    // default emptyGracePolls = 4 → polls at guard 0..5, six fetches total
    expect(fetchEvents.mock.calls.length).toBe(6)
    expect(tail.running.value).toBe(false)
  })
})

describe('台账页接线面契约（单通道禁第二轮询，终态静默重读）', () => {
  const api = readFileSync(resolve(here, '../../src/api/webElements.ts'), 'utf-8')
  const ledger = readFileSync(
    resolve(here, '../../src/views/webelement/WebElementLedgerView.vue'), 'utf-8')

  it('api 镜像走共享 request，路径/游标参数与后端读门一致', () => {
    expect(api).toContain('`/web-elements/survey/${sessionId}/process-events`')
    expect(api).toMatch(/params: limit === undefined \? \{ cursor \} : \{ cursor, limit \}/)
  })

  it('发起成功即开尾窗，终态后静默重读列表；徽标与抽屉同游标单源', () => {
    expect(ledger).toContain("import TaskTailDrawer from '@/components/TaskTailDrawer.vue'")
    expect(ledger).toContain('useTestRunTail')
    expect(ledger).toContain('fetchSurveyProcessEvents')
    expect(ledger).toContain('openRun')
    expect(ledger).toContain('ledger-survey-live')
    expect(ledger).toContain('surveyTailOpen')
    // terminal collapse reloads the ledger silently — no manual-refresh copy
    expect(ledger).toMatch(/openRun[\s\S]{0,400}then[\s\S]{0,80}load\(/)
    expect(ledger).not.toContain('setInterval')
  })
})

describe('locale 双轨键齐（i18n 防半缺）', () => {
  const zh = readFileSync(resolve(here, '../../src/utils/locales/zh-CN.ts'), 'utf-8')
  const en = readFileSync(resolve(here, '../../src/utils/locales/en-US.ts'), 'utf-8')

  it('进行中徽标与尾窗入口文案 zh/en 同时存在', () => {
    expect(zh).toContain('tailRunning:')
    expect(en).toContain('tailRunning:')
  })
})

describe('R62 值面守卫：survey 域文案禁手动刷新类指令句式', () => {
  const zh = readFileSync(resolve(here, '../../src/utils/locales/zh-CN.ts'), 'utf-8')
  const en = readFileSync(resolve(here, '../../src/utils/locales/en-US.ts'), 'utf-8')

  // survey namespace holds the dispatch/batch copy of the ledger page; the
  // tail window auto-collapses and silently reloads, so any "press refresh"
  // instruction here would push users onto a dead-end path (R62/R63).
  function surveyBlock(source: string): string {
    const match = source.match(/survey: \{[\s\S]*?\n {4}\}/)
    expect(match, 'survey block must exist in locale file').not.toBeNull()
    return match[0]
  }

  const zhForbidden = ZH_REFRESH_INSTRUCTION
  const enForbidden = EN_REFRESH_INSTRUCTION

  // The exploration-session kickoff receipt shares the same rule as the
  // survey door: a "create" ack that tells the viewer to refresh pushes
  // them onto a manual-refresh path instead of the tail window (R62 value
  // face). One key line per locale keeps the sweep scoped and symmetric.
  function keyLine(source: string, key: string): string {
    const m = source.match(new RegExp(`^\\s*${key}: .*`, 'm'))
    expect(m, `${key} must exist in locale file`).not.toBeNull()
    return m[0]
  }

  it('探索创建回执 createNoSession 不含手动刷新指令句式（zh/en 对称）', () => {
    expect(keyLine(zh, 'createNoSession')).not.toMatch(zhForbidden)
    expect(keyLine(en, 'createNoSession')).not.toMatch(enForbidden)
  })

  it('测绘发起与批量批准文案不含手动刷新指令句式', () => {
    expect(surveyBlock(zh)).not.toMatch(zhForbidden)
    expect(surveyBlock(en)).not.toMatch(enForbidden)
  })

  it('探索详情步骤空态 stepsEmpty 不含稍后刷新类指令句式（zh/en 对称）', () => {
    expect(keyLine(zh, 'stepsEmpty')).not.toMatch(zhForbidden)
    expect(keyLine(en, 'stepsEmpty')).not.toMatch(enForbidden)
  })

  // value-face extension: the judge report's cards-empty hint used to
  // push queued runs onto "refresh later". The scoring ledger re-reads on
  // landing, so the instruction pattern is banned here under the same rule.
  it('判定报告空态提示 cardsEmptyHint 不含稍后刷新指令句式（zh/en 对称）', () => {
    expect(keyLine(zh, 'cardsEmptyHint')).not.toMatch(zhForbidden)
    expect(keyLine(en, 'cardsEmptyHint')).not.toMatch(enForbidden)
  })

  // W-group object guard (vision-door finding): the exploration terminate
  // button names its object instead of standing as a bare verb.
  it('探索终止钮文案带动宾宾语（zh 含「探索」/en 含 exploration）', () => {
    expect(keyLine(zh, 'terminate')).toContain('探索')
    expect(keyLine(en, 'terminate')).toMatch(/exploration/i)
  })

  it('负样本自证：句式确实会被拦（防守卫静默失效）', () => {
    expect('可先切换状态筛选或直接刷新').toMatch(zhForbidden)
    expect('暂无步骤（探索执行回退旧句：稍后刷新）').toMatch(zhForbidden)
    expect('switch the state filter or refresh').toMatch(enForbidden)
    expect('exploration running, refresh later').toMatch(enForbidden)
    expect('failed — refresh and handle them one by one').toMatch(enForbidden)
  })
})

// value-face extension (K side): the two degraded frames lived in the
// backend process-event pump before this batch — heartbeat frame pushed
// The pump feeds every tail window, so its on-screen frame strings share the
// same sentence-family ban and are swept from this single guard file.
describe('R62 值面守卫：后端过程事件泵上屏帧句式（K 面）', () => {
  const pump = readFileSync(
    resolve(
      here,
      '../../../admin/src/main/java/com/sixbean/testpike/admin/ai/app/service/ProcessEventPump.java'
    ),
    'utf-8'
  )

  // User-visible frames are Chinese literals passed to writer.write; pull
  // quoted CJK segments so comments and identifiers never widen the scope.
  function cjkLiterals(source: string): string[] {
    const out: string[] = []
    const re = /"([^"\\\n]*[一-鿿][^"\\\n]*)"/g
    let m: RegExpExecArray | null
    while ((m = re.exec(source))) out.push(m[1])
    return out
  }

  it('泵上屏中文帧零手动刷新/重开指令句式', () => {
    const frames = cjkLiterals(pump)
    // Self-evidence the sweep executes on a non-empty face (R43): an empty
    // capture would silently pass every assertion below.
    expect(frames.length).toBeGreaterThan(3)
    frames.forEach(frame => expect(frame).not.toMatch(ZH_REFRESH_INSTRUCTION))
  })

  it('上限帧给重连动作语义、心跳帧保留将继续跟随（终态指路不空窗）', () => {
    expect(pump).toContain('重连后可继续跟随')
    expect(pump).toContain('任务仍在进行，将继续跟随')
  })

  it('负样本自证：整改前的泵旧句式必被同一判据拦下', () => {
    expect('任务状态请以刷新后的列表为准').toMatch(ZH_REFRESH_INSTRUCTION)
    expect('也可刷新页面查看最新状态').toMatch(ZH_REFRESH_INSTRUCTION)
    expect('可稍后重开向导查看结果').toMatch(ZH_REFRESH_INSTRUCTION)
    expect('Self-heal records are unavailable right now — reopen this dialog later').toMatch(
      EN_REFRESH_INSTRUCTION
    )
  })
})

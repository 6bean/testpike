/**
 * (R62) explore-tail wiring contract: the exploration detail
 * page replays the session's process tail through the SHARED tail base and
 * the SHARED drawer — one cursor channel auto-opened while the session is
 * running, tail-cap trim, terminal collapse plus a silent detail reload, and
 * NO manual-refresh affordance left on the process surface. Guard family
 * mirrors webElementSurveyTailContract.test.ts (same judgement shape, no
 * second gate, R40).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { isExploreTerminalRow } from '@/api/explore'
import { useTestRunTail, type TestRunEvent } from '@/composables/useTestRunTail'

const here = dirname(fileURLToPath(import.meta.url))

function read(rel: string): string {
  return readFileSync(resolve(here, rel), 'utf8')
}

const viewSrc = read('../../src/views/explore/ExploreDetailView.vue')
const zhLocales = read('../../src/utils/locales/zh-CN.ts')
const enLocales = read('../../src/utils/locales/en-US.ts')

function event(cursor: number, summary: string, level = 'step'): TestRunEvent {
  return { cursor, level, summary, detail: null, landedAt: `2026-11-18T09:0${cursor}:00` }
}

describe('isExploreTerminalRow：探索尾窗终态帧两族前缀单源命中', () => {
  it('循环出口三型与失败成形线都算终态帧', () => {
    expect(isExploreTerminalRow(event(1, '探索循环出口：目标达成（步数 12，页数 8，token 90000）'))).toBe(true)
    expect(isExploreTerminalRow(event(2, '探索循环出口：预算耗尽（部分完成）（步数 40，页数 22，token 300000）'))).toBe(true)
    expect(isExploreTerminalRow(event(3, '失败：【模型服务不可达】上游超时，可在探索会话页重新发起探索'))).toBe(true)
  })

  it('思考帧与步帧不是终态（尾窗不得中途收档）', () => {
    expect(isExploreTerminalRow(event(4, '步骤 3｜打开 https://a｜观测 标题 A'))).toBe(false)
    expect(isExploreTerminalRow(event(5, '思考：下一步点击登录入口'))).toBe(false)
  })

  it('前缀值面与后端写侧源码对账（写侧句子漂移即红）', () => {
    const kernel = read('../../../ai-service/src/main/java/com/sixbean/testpike/aiservice/explore/domain/component/ExploreLoopKernel.java')
    const formatter = read('../../../ai-service/src/main/java/com/sixbean/testpike/aiservice/taskstream/domain/component/TaskFailureEventFormatter.java')
    expect(kernel).toContain('"探索循环出口："')
    expect(formatter).toContain('"失败：【"')
  })
})

describe('ExploreDetailView 尾窗接线形态（消费共享底座单源）', () => {
  it('只 import 底座两文件，不复制第二套窗口实现', () => {
    expect(viewSrc).toContain("import TaskTailDrawer from '@/components/TaskTailDrawer.vue'")
    expect(viewSrc).toContain("import { useTestRunTail } from '@/composables/useTestRunTail'")
    expect(viewSrc).not.toMatch(/setInterval|setTimeout\([^)]*load/)
  })

  it('手动刷新钮已摘除，过程可见性由尾窗承载（R62④）', () => {
    expect(viewSrc).not.toContain('explore-detail-refresh')
    expect(viewSrc).toContain('data-test="explore-detail-tail"')
    expect(viewSrc).toContain('data-test="explore-detail-tail-badge"')
  })

  it('终态静默重读在位（openRun 收轮后 load，禁屏显刷新指令）', () => {
    expect(viewSrc).toContain('ensureTailRun')
    expect(viewSrc).toMatch(/openRun\(\{ runToken: String\(id\) \}\)\.then\(\(\) =>/)
  })

  it('zh/en 徽标键对称，探索回执文案无「刷新」指令句', () => {
    expect(zhLocales).toContain("tailBadge: '过程进行中'")
    expect(enLocales).toContain("tailBadge: 'Process running'")
    const zhNoSession = zhLocales.match(/createNoSession: '[^']*'/)![0]
    const enNoSession = enLocales.match(/createNoSession: '[^']*'/)![0]
    expect(zhNoSession).not.toContain('刷新')
    expect(enNoSession.toLowerCase()).not.toContain('refresh')
  })
})

describe('useTestRunTail 探索异步模式行为（游标回放+终态收档+尾剪）', () => {
  it('批次回放到终态帧即收档，游标幂等推进', async () => {
    const batches = [[event(1, '步骤 1｜打开起点'), event(2, '思考：点击入口')], [event(3, '探索循环出口：目标达成（步数 2，页数 1，token 10）')]]
    let call = 0
    const fetchEvents = vi.fn(async () => batches[Math.min(call++, batches.length - 1)])
    const tail = useTestRunTail(fetchEvents, {
      terminal: isExploreTerminalRow, emptyGracePolls: 30, maxPolls: 100, closeOnTerminal: true, pollIntervalMs: 1,
    })
    await tail.openRun({ runToken: '77' })
    expect(tail.events.value.map((e) => e.cursor)).toEqual([1, 2, 3])
    expect(tail.drawerOpen.value).toBe(false)
    expect(tail.running.value).toBe(false)
    expect(tail.interrupted.value).toBe(false)
  })

  it('无终态帧时诚实降级（interrupted 上屏，禁静默空窗）', async () => {
    const fetchEvents = vi.fn(async () => [event(1, '步骤 1｜进行中')])
    const tail = useTestRunTail(fetchEvents, {
      terminal: isExploreTerminalRow, emptyGracePolls: 1, maxPolls: 2, closeOnTerminal: true, pollIntervalMs: 1,
    })
    await tail.openRun({ runToken: '78' })
    expect(tail.interrupted.value).toBe(true)
    expect(tail.drawerOpen.value).toBe(true)
  })

  it('尾剪上限只保最新帧（容器内容增长受热的上限约束）', async () => {
    let cursor = 0
    const fetchEvents = vi.fn(async () => {
      cursor += 1
      if (cursor <= 2) return [event(cursor, `步骤 ${cursor}｜进行中`)]
      return [event(cursor, '探索循环出口：目标达成（步数 3，页数 1，token 1）')]
    })
    const tail = useTestRunTail(fetchEvents, {
      terminal: isExploreTerminalRow, emptyGracePolls: 5, maxPolls: 20, closeOnTerminal: true, pollIntervalMs: 1, cap: 2,
    })
    await tail.openRun({ runToken: '79' })
    expect(tail.events.value.length).toBeLessThanOrEqual(2)
    expect(tail.events.value[tail.events.value.length - 1].cursor).toBe(3)
  })
})

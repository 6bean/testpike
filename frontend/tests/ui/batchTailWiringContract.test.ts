/**
 * (R62) batch-tail wiring contract: the batch detail page
 * replays the execution process tail through the SHARED tail base and the
 * SHARED drawer — one cursor channel auto-opened while the batch sits in the
 * running window, tail-cap trim, terminal collapse plus a silent list
 * re-read, and NO refresh-instruction copy on the process surface. Guard
 * family mirrors exploreTailWiringContract.test.ts (same judgement shape, no
 * second gate, R40).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { BATCH_TERMINAL_PREFIX, isBatchTerminalRow } from '@/api/batch'

const here = dirname(fileURLToPath(import.meta.url))

function read(rel: string): string {
  return readFileSync(resolve(here, rel), 'utf8')
}

const viewSrc = read('../../src/views/batch/BatchDetailView.vue')
const apiSrc = read('../../src/api/batch.ts')
const finalizeSrc = read(
  '../../../core/src/main/java/com/sixbean/testpike/core/batch/domain/component/BatchFinalizeSupport.java')
const zhLocales = read('../../src/utils/locales/zh-CN.ts')
const enLocales = read('../../src/utils/locales/en-US.ts')

/** segment one top-level locale block by key (e.g. 'batch') — both locale files
 * hold it exactly once at the two-space indent used by the page sections. */
function localeBlock(src: string, key: string): string {
  const start = src.indexOf(`\n  ${key}: {`)
  expect(start, `locale block ${key} missing`).toBeGreaterThan(-1)
  const end = src.indexOf('\n  },', start)
  expect(end, `locale block ${key} unterminated`).toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('isBatchTerminalRow：批次尾窗终态帧单前缀族命中', () => {
  it('完成/部分失败/取消/看门狗四型收档句都算终态帧', () => {
    expect(isBatchTerminalRow({ summary: `${BATCH_TERMINAL_PREFIX}｜全部用例通过` })).toBe(true)
    expect(isBatchTerminalRow({ summary: `${BATCH_TERMINAL_PREFIX}｜存在失败用例，明细见批次报告` })).toBe(true)
    expect(isBatchTerminalRow({ summary: `${BATCH_TERMINAL_PREFIX}｜批次已取消（1 个未派发任务出队，已完成结果保留）` })).toBe(true)
    expect(isBatchTerminalRow({ summary: `${BATCH_TERMINAL_PREFIX}｜超时看门狗自动取消（2 个未派发任务出队）` })).toBe(true)
  })

  it('发起/派发/用例级帧不是终态（尾窗不得中途收档）', () => {
    expect(isBatchTerminalRow({ summary: '批次执行已发起｜共 3 条用例进入执行队列' })).toBe(false)
    expect(isBatchTerminalRow({ summary: '用例已派发｜任务 12（用例 3）由执行器 7 领取' })).toBe(false)
    expect(isBatchTerminalRow({ summary: '用例执行失败｜任务 12｜耗时 1200ms' })).toBe(false)
  })

  it('前缀值面与后端写侧源码对账（写侧句子漂移即红）', () => {
    expect(finalizeSrc).toContain('"批次执行结束｜全部用例通过"')
    expect(finalizeSrc).toContain('"批次执行结束｜存在失败用例，明细见批次报告"')
    expect(finalizeSrc).toContain('"批次执行结束｜批次已取消（"')
    expect(finalizeSrc).toContain('"批次执行结束｜超时看门狗自动取消（"')
  })
})

describe('BatchDetailView 尾窗接线形态（消费共享底座单源）', () => {
  it('只 import 底座两文件与共享热更键，不复制第二套窗口实现', () => {
    expect(viewSrc).toContain("import TaskTailDrawer from '@/components/TaskTailDrawer.vue'")
    expect(viewSrc).toContain("import { useTestRunTail } from '@/composables/useTestRunTail'")
    expect(viewSrc).toContain('surveyTailCap')
    expect(viewSrc).toContain('tailTerminalDwellMs')
    // the API layer rides the shared row contract, no local copy of it
    expect(apiSrc).toContain("import type { TestRunEvent } from '@/composables/useTestRunTail'")
    expect(apiSrc).toContain('export { BATCH_TERMINAL_PREFIX }')
  })

  it('徽标与尾窗锚点在位，过程可见性由尾窗承载（R62①②）', () => {
    expect(viewSrc).toContain('data-test="batch-detail-tail-badge"')
    expect(viewSrc).toContain('data-test="batch-detail-tail"')
    // badge gated on the backend row state, not the front loop state
    expect(viewSrc).toMatch(/POLL_STATUSES\.includes\(batch\.status\)[\s\S]*batch-detail-tail-badge/)
  })

  it('终态静默重读在位（openRun 收轮后 loadBatch+loadCases，禁屏显刷新指令）', () => {
    expect(viewSrc).toContain('ensureTailRun')
    expect(viewSrc).toMatch(/openRun\(\{ runToken: String\(id\) \}\)\.then\(\(\) =>/)
    expect(viewSrc).not.toMatch(/batch[\s\S]{0,400}?(手动刷新|点击刷新)/)
  })
})

describe('值面守卫：批次发起/取消回执与徽标文案（zh/en 对称，无刷新指令句式）', () => {
  it('zh/en 徽标键对称', () => {
    expect(localeBlock(zhLocales, 'batch')).toContain("tailBadge: '过程进行中'")
    expect(localeBlock(enLocales, 'batch')).toContain("tailBadge: 'Process running'")
  })

  it('批次回执文案族零「刷新/refresh」指令句（R62④）', () => {
    const zh = localeBlock(zhLocales, 'batch')
    const en = localeBlock(enLocales, 'batch')
    for (const key of ['startOk', 'startFail', 'cancelBatchOk', 'cancelBatchFail']) {
      expect(zh, `zh batch.${key}`).not.toMatch(new RegExp(`${key}:[^\\n]*刷新`))
      expect(en, `en batch.${key}`).not.toMatch(new RegExp(`${key}:[^\\n]*refresh`, 'i'))
    }
  })
})

/**
 * V615 shared-pump shape gate (display-layer duplication family): the batch
 * face merges several per-task ledgers into one drawer, so (a) the merge key
 * must be scope+cursor — bare cursors only stay unique inside one source —
 * and (b) an SSE frame without `id:` must never be parked under a fabricated
 * cursor: the poll window would then re-append the same event under its true
 * ledger id, and the viewer sees one error twice. Behavioural pair lives in
 * genBatchEventTail.test.ts (merged-scope spectra); this gate pins that the
 * single shared implementation keeps both shapes.
 */
describe('共享尾泵形态闸（scope 键合并不再互抹，无 id 帧不再伪造游标）', () => {
  const pumpSrc = read('../../src/api/taskEvents.ts')
  const batchComposableSrc = read('../../src/composables/useGenBatchEventTail.ts')

  it('mergeEvents 以 scope+cursor 为键（多账合并互抹归零）', () => {
    expect(pumpSrc).toContain('function mergeKey')
    expect(pumpSrc).toContain('row.scope ?? ')
    expect(pumpSrc).not.toMatch(/byCursor\.set\(row\.cursor/)
  })

  it('SSE 无 id 帧改判为「立即轮询取真账」，伪造游标分支摘除', () => {
    expect(pumpSrc).toContain('frame.id === null')
    expect(pumpSrc).not.toContain('frame.id ?? lastCursor + 1')
  })

  it('批量面给每行盖来源任务章（跨账游标同值不互斥）', () => {
    expect(batchComposableSrc).toContain('scope: String(item.taskId)')
  })
})

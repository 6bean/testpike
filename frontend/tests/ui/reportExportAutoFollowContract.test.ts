/**
 * G-face (R62) report export-task auto-follow contract: the export task
 * panel keeps its statuses fresh through the SHARED useListAutoFollow beat
 * (one loop implementation for every list panel, R40) — the old submit-side
 * 60x2s pollTask loop and the 10s mount interval are both retired, the async
 * submission re-reads once and the beat settles the row on its own, so the
 * user never needs a manual refresh and the page ships no refresh-instruction
 * copy (the retired timeout key must stay at zero residual). The pending
 * judgement is the complement of the terminal states stamped by the core
 * writer side (ExportTaskSupport), value face re-checked against that source
 * here. Guard family mirrors journeyRunAutoFollowContract.test.ts (same
 * judgement shape, R40).
 */
import { describe, expect, it } from 'vitest'
import {
  expectNoSecondPollingChannel,
  expectSharedBeatImports,
  readTestSource,
} from './autoFollowWiringShared'

const viewSrc = readTestSource('../../src/views/report/ReportView.vue')
const writerSrc = readTestSource(
  '../../../core/src/main/java/com/sixbean/testpike/core/report/domain/component/ExportTaskSupport.java')
const zhLocales = readTestSource('../../src/utils/locales/zh-CN.ts')
const enLocales = readTestSource('../../src/utils/locales/en-US.ts')

/** segment the nested `report` locale block (2-space indent at top level). */
function reportBlock(src: string): string {
  const start = src.indexOf('\n  report: {')
  expect(start, 'locale block report missing').toBeGreaterThan(-1)
  const end = src.indexOf('\n  },', start)
  expect(end, 'locale block report unterminated').toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('报告导出任务 接线：消费共享跟随节拍，退役第二通道', () => {
  it('引入 useListAutoFollow 与热更节拍键单源', () => {
    expectSharedBeatImports(viewSrc)
  })

  it('页面自身不再出现第二套轮询通道（R40/R62①）', () => {
    expectNoSecondPollingChannel(viewSrc)
    expect(viewSrc).not.toContain('pollTask')
  })

  it('异步提交先静默重读一次再交给跟随拍（列表当场出现处理中行）', () => {
    expect(viewSrc).toMatch(/await refreshTasks\(\)/)
    expect(viewSrc).toContain('exportFollow.start()')
    expect(viewSrc).toMatch(/reload: async \(\) => \{ await refreshTasks\(\) \}/)
  })

  it('跟随徽标锚点在位，重读不亮全局骨架', () => {
    expect(viewSrc).toContain('data-test="report-export-following"')
    expect(viewSrc).toContain('v-if="exportFollowing"')
    expect(viewSrc).toContain('const exportFollowing = exportFollow.following')
  })
})

describe('进行中的判定值面：与 core 写侧终态集互补对账', () => {
  it('视图终态集 {2, 3}，进行中=其余（写侧 1=处理中 / 2=完成 / 3=失败 锚）', () => {
    expect(viewSrc).toContain('new Set([2, 3])')
    expect(viewSrc).toMatch(/isPending: \(row\) => !EXPORT_TERMINAL\.has\(row\.status\)/)
    expect(writerSrc).toContain('SET status = 2')
    expect(writerSrc).toContain('SET status = 3')
    expect(writerSrc).toContain('status=1')
  })
})

describe('文案守卫：跟随句非指令句式，超时指令键双语退役零残留', () => {
  it('zh/en report 块均含 exportFollowing 且徽标段不含刷新指令族句式', () => {
    const zh = reportBlock(zhLocales)
    const en = reportBlock(enLocales)
    expect(zh).toContain('exportFollowing:')
    expect(en).toContain('exportFollowing:')
    const zhBadge = zh.slice(zh.indexOf('exportFollowing:'), zh.indexOf('exportFollowing:') + 120)
    const enBadge = en.slice(en.indexOf('exportFollowing:'), en.indexOf('exportFollowing:') + 160)
    expect(zhBadge).not.toMatch(/请刷新|刷新后|刷新页面|稍后重开/)
    expect(enBadge).not.toMatch(/refresh (the page|again)|please refresh/i)
  })

  it('退役键 exportTimeout 在双语 locale 与视图消费面零残留', () => {
    expect(zhLocales).not.toContain('exportTimeout')
    expect(enLocales).not.toContain('exportTimeout')
    expect(viewSrc).not.toContain('report.exportTimeout')
  })
})

/**
 * R61 same-batch help amendment: the statistics report chapter must carry
 * the behaviour section for the export-task auto-follow change, appended
 * through the same idempotent migration (marker-heading NOT LIKE guard,
 * title anchor for cross-slot stability). Shape mirrors the journey
 * contract companion.
 */
const v582Migration = readTestSource('../../../db/migration/V582__help_journey_report_auto_follow.sql')

describe('R61 帮助章同批改：统计报表章追加「导出任务状态自动更新」节（幂等守卫）', () => {
  it('迁移件含节名、域锚定、站内信事实与幂等守卫，且节面零刷新指令句式', () => {
    expect(v582Migration).toContain('导出任务状态自动更新')
    expect(v582Migration).toContain("domain_code = 'Q'")
    expect(v582Migration).toContain("title LIKE '统计报表%'")
    expect(v582Migration).toContain('站内信')
    const writerBlock = v582Migration.slice(
      v582Migration.indexOf('导出任务状态自动更新'),
      v582Migration.indexOf("domain_code = 'Q'"))
    expect(writerBlock).not.toMatch(/(手动刷新|点击刷新|刷新页面|刷新后重试|稍后重开)/)
    // The completion notice claim must stay true on the core writer side.
    expect(writerSrc).toContain('EXPORT_DONE')
  })
})

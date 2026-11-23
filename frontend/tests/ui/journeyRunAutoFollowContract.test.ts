/**
 * F-face (R62) journey run timeline auto-follow contract: the timeline
 * keeps its own status fresh through the SHARED useListAutoFollow beat (one
 * loop implementation for every list panel, R40) — a run that is still
 * moving updates on screen without any manual refresh, and the page ships no
 * refresh-instruction copy about the in-flight run. The pending judgement is
 * the complement of the terminal set stamped by the core writer side, value
 * face re-checked against that source here. Guard family mirrors
 * mockJobsAutoFollowContract.test.ts (same judgement shape, R40).
 */
import { describe, expect, it } from 'vitest'
import {
  expectNoSecondPollingChannel,
  expectSharedBeatImports,
  readTestSource,
} from './autoFollowWiringShared'

const viewSrc = readTestSource('../../src/views/journey/JourneyRunTimelineView.vue')
const runSvcSrc = readTestSource(
  '../../../core/src/main/java/com/sixbean/testpike/core/journey/domain/service/impl/JourneyRunServiceImpl.java')
const zhLocales = readTestSource('../../src/utils/locales/zh-CN.ts')
const enLocales = readTestSource('../../src/utils/locales/en-US.ts')

/** segment the nested `journeys` locale block (2-space indent at top level). */
function journeysBlock(src: string): string {
  const start = src.indexOf('\n  journeys: {')
  expect(start, 'locale block journeys missing').toBeGreaterThan(-1)
  const end = src.indexOf('\n  },', start)
  expect(end, 'locale block journeys unterminated').toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('journey timeline 接线：消费共享跟随节拍，不另起第二通道', () => {
  it('引入 useListAutoFollow 与热更节拍键单源', () => {
    expectSharedBeatImports(viewSrc)
  })

  it('页面自身不再出现第二套轮询通道（R40/R62①）', () => {
    expectNoSecondPollingChannel(viewSrc)
  })

  it('跟随徽标锚点在位，重读拍走静默参数不误亮骨架', () => {
    expect(viewSrc).toContain('data-test="journey-run-following"')
    expect(viewSrc).toMatch(/async function loadRun\(silent = false\)/)
    expect(viewSrc).toMatch(/reload: async \(\) => \{ await loadRun\(true\) \}/)
  })

  it('手动刷新按钮显式无参调用，点击与跟随拍语义分离', () => {
    expect(viewSrc).toContain('@click="loadRun()"')
  })
})

describe('进行中的判定值面：与 core 写侧终态集互补对账', () => {
  it('视图终态集 {2,3,4}，进行中=其余（含 RUN_RUNNING=1 写侧锚）', () => {
    expect(viewSrc).toContain('new Set([2, 3, 4])')
    expect(viewSrc).toMatch(/isPending: \(row\) => !TERMINAL\.has\(row\.status\)/)
    expect(runSvcSrc).toContain('RUN_RUNNING = 1')
  })
})

describe('文案守卫：跟随句非指令句式，双语对称', () => {
  it('zh/en journeys 块均含 runFollowing 且不含刷新指令族句式', () => {
    const zh = journeysBlock(zhLocales)
    const en = journeysBlock(enLocales)
    expect(zh).toContain('runFollowing:')
    expect(en).toContain('runFollowing:')
    const zhBadge = zh.slice(zh.indexOf('runFollowing:'), zh.indexOf('runFollowing:') + 120)
    const enBadge = en.slice(en.indexOf('runFollowing:'), en.indexOf('runFollowing:') + 160)
    expect(zhBadge).not.toMatch(/请刷新|刷新后|刷新页面|稍后重开/)
    expect(enBadge).not.toMatch(/refresh (the page|again)|please refresh/i)
  })
})

/**
 * R61 same-batch help amendment: the journey orchestration chapter must
 * carry the behaviour section for the auto-follow change, appended through
 * an idempotent migration (marker-heading NOT LIKE guard, title anchor for
 * cross-slot stability). Shape mirrors mockJobsAutoFollowContract.test.ts.
 */
const v582Migration = readTestSource('../../../db/migration/V582__help_journey_report_auto_follow.sql')

describe('R61 帮助章同批改：旅程编排章追加「旅程执行状态自动更新」节（幂等守卫）', () => {
  it('迁移件含节名、域锚定与幂等守卫，且节面零刷新指令句式', () => {
    expect(v582Migration).toContain('旅程执行状态自动更新')
    expect(v582Migration).toContain("domain_code = 'I'")
    expect(v582Migration).toContain("title LIKE '旅程编排%'")
    expect(v582Migration).toContain('NOT LIKE')
    const section = v582Migration.slice(
      v582Migration.indexOf('旅程执行状态自动更新'),
      v582Migration.indexOf("domain_code = 'I'"))
    expect(section).not.toMatch(/(手动刷新|点击刷新|刷新页面|刷新后重试|稍后重开)/)
  })
})

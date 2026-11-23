/**
 * H-face (R62) upgrade-plan list auto-follow contract: the plan
 * progress panel keeps RUNNING-row statuses fresh through the SHARED
 * useListAutoFollow beat (one loop implementation for every list panel,
 * R40) — the page ships no second polling channel, the async start hands
 * freshness to the beat right after its first re-read, and the badge
 * self-retires when nothing runs anymore, so the user never needs a manual
 * refresh and the panel ships no refresh-instruction copy. The pending
 * judgement is the RUNNING member of core's own state machine
 * (UpgradePlanServiceImpl; terminal = COMPLETED/ABORTED/BRAKED), value face
 * re-checked against that source here. Guard family mirrors
 * reportExportAutoFollowContract.test.ts (same judgement shape, R40).
 */
import { describe, expect, it } from 'vitest'
import {
  expectNoSecondPollingChannel,
  expectSharedBeatImports,
  readTestSource,
} from './autoFollowWiringShared'

const viewSrc = readTestSource('../../src/views/cluster/UpgradeCenterView.vue')
const writerSrc = readTestSource(
  '../../../core/src/main/java/com/sixbean/testpike/core/cluster/domain/service/impl/UpgradePlanServiceImpl.java')
const zhLocales = readTestSource('../../src/utils/locales/zh-CN.ts')
const enLocales = readTestSource('../../src/utils/locales/en-US.ts')

/** segment the nested `upgrade` locale block (2-space indent at top level). */
function upgradeBlock(src: string): string {
  const start = src.indexOf('\n  upgrade: {')
  expect(start, 'locale block upgrade missing').toBeGreaterThan(-1)
  const end = src.indexOf('\n  },', start)
  expect(end, 'locale block upgrade unterminated').toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('升级计划列表 接线：消费共享跟随节拍，零第二通道', () => {
  it('引入 useListAutoFollow 与热更节拍键单源', () => {
    expectSharedBeatImports(viewSrc)
  })

  it('页面自身不再出现第二套轮询通道（R40/R62①）', () => {
    expectNoSecondPollingChannel(viewSrc)
  })

  it('取数单源：手动读与跟随拍共用 fetchPlanRows（R40）', () => {
    const reads = viewSrc.match(/plans\.value = await listUpgradePlans\(\)/g) ?? []
    expect(reads.length).toBe(1)
    expect(viewSrc).toContain('await fetchPlanRows()')
  })

  it('异步发起先重读一次再交给跟随拍（列表当场出现 RUNNING 行）', () => {
    expect(viewSrc).toMatch(
      /resetPlanFlow\(\)[\s\S]{0,120}await loadPlans\(\)[\s\S]{0,200}planFollow\.start\(\)/)
    expect(viewSrc).toMatch(/reload: async \(\) => \{[\s\S]*?await fetchPlanRows\(\)/)
  })

  it('跟随徽标锚点在位，重读不亮全局骨架', () => {
    expect(viewSrc).toContain('data-test="upgrade-plan-following"')
    expect(viewSrc).toContain('v-if="planFollowing"')
    expect(viewSrc).toContain('const planFollowing = planFollow.following')
    expect(viewSrc).toContain('class="follow-badge"')
  })
})

describe('进行中的判定值面：与 core 状态机常量对账', () => {
  it('进行中=RUNNING（后台推进态）；终态与等待人工的 PAUSED/PREVIEW 不跟随', () => {
    expect(viewSrc).toContain("isPending: (row) => row.status === 'RUNNING'")
    expect(writerSrc).toContain('static final String RUNNING = "RUNNING";')
    expect(writerSrc).toContain('static final String COMPLETED = "COMPLETED";')
    expect(writerSrc).toContain('static final String ABORTED = "ABORTED";')
    expect(writerSrc).toContain('static final String BRAKED = "BRAKED";')
  })
})

describe('文案守卫：跟随句非指令句式，徽标双语对称', () => {
  it('zh/en upgrade 块均含 planFollowing 且徽标段不含刷新指令族句式', () => {
    const zh = upgradeBlock(zhLocales)
    const en = upgradeBlock(enLocales)
    expect(zh).toContain('planFollowing:')
    expect(en).toContain('planFollowing:')
    const zhBadge = zh.slice(zh.indexOf('planFollowing:'), zh.indexOf('planFollowing:') + 60)
    const enBadge = en.slice(en.indexOf('planFollowing:'), en.indexOf('planFollowing:') + 90)
    expect(zhBadge).not.toMatch(/请刷新|刷新后|刷新页面|稍后重开/)
    expect(enBadge).not.toMatch(/refresh (the page|again)|please refresh/i)
  })
})

/**
 * R61 same-batch help amendment: the upgrade-plan chapter carries the
 * behaviour section for the auto-follow change, appended through the same
 * idempotent migration (marker-heading NOT LIKE guard, domain_code + title
 * anchor for cross-slot stability). Shape mirrors V582 (R61 companions).
 */
const v583Migration = readTestSource('../../../db/migration/V583__help_upgrade_plan_auto_follow.sql')
const themeSrc = readTestSource('../../src/styles/theme.scss')

describe('R61 帮助章同批改：升级计划章追加「升级计划状态自动更新」节（幂等守卫）', () => {
  it('迁移件含节名、域锚定、幂等守卫，且节面零刷新指令句式', () => {
    expect(v583Migration).toContain('升级计划状态自动更新')
    expect(v583Migration).toContain("domain_code = 'K'")
    expect(v583Migration).toContain("title LIKE '升级计划%'")
    expect(v583Migration).toContain("body_md NOT LIKE '%升级计划状态自动更新%'")
    const section = v583Migration.slice(v583Migration.indexOf('升级计划状态自动更新'))
    expect(section).not.toMatch(/(手动刷新|点击刷新|刷新页面|刷新后重试|稍后重开)/)
  })
})

describe('徽标样式单源：theme 一处，消费页禁 scoped 复制（红线10/R40）', () => {
  it('theme.scss 定义 .follow-badge，升级页与报表页零副本', () => {
    expect(themeSrc).toContain('.follow-badge {')
    expect(viewSrc).not.toContain('.follow-badge {')
    const reportSrc = readTestSource('../../src/views/report/ReportView.vue')
    expect(reportSrc).not.toContain('.follow-badge {')
  })
})

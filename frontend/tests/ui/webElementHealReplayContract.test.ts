/**
 * §8.4 replay-surface contract (self-heal evidence presentation).
 * Pure-logic single source (utils/webElementHealReport) + api mirror +
 * locale keys: the report badge/diff/summary decisions and the ledger
 * recent-heal reference read must stay in one place (R40) and the two
 * batch numbers (heal rate / assertion mismatches) must always be
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  buildHealBadgeItems, buildHealSummary, countAssertionMismatches,
  formatHealRate, healAnchorDiffText, isAssertionStep,
} from '@/utils/webElementHealReport'
import type { StepHealEvidenceRow } from '@/api/webElements'

const here = dirname(fileURLToPath(import.meta.url))

function row(stepNo: number | null, over: Partial<StepHealEvidenceRow> = {}): StepHealEvidenceRow {
  return {
    id: stepNo ?? 0,
    taskId: 7,
    elementId: 11,
    stepNo,
    healed: true,
    firstAnchorFailed: true,
    fromAnchorType: 'xpath',
    fromAnchorValue: '//div[@id="a"]',
    usedAnchorType: 'text',
    usedAnchorValue: '提交',
    viewport: 'desktop',
    ledgerRevision: 7,
    similarityScore: 0.91,
    createdAt: '2026-11-18T00:30:00',
    ...over,
  }
}

describe('buildHealBadgeItems：证据行按步号配对步骤行', () => {
  it('命中步出徽标项，未落步号的证据进 orphan 不静默丢', () => {
    const steps = [{ stepNo: 1 }, { stepNo: 2, action: 'click' }]
    const { items, orphanRows } = buildHealBadgeItems(steps, [row(2), row(9), row(null)])
    expect(items).toHaveLength(1)
    expect(items[0].stepNo).toBe(2)
    expect(orphanRows).toHaveLength(2)
  })

  it('同步号多条证据归一项内（一步多次重锚审计全留）', () => {
    const { items } = buildHealBadgeItems([{ stepNo: 3 }], [row(3, { id: 1 }), row(3, { id: 2 })])
    expect(items[0].rows).toHaveLength(2)
  })
})

describe('断言失配计数：只算断言族失败步（服务端动词表单源，非启发式）', () => {
  it('assertVisible/assertNotVisible/assertText 失败计入，click 失败与非断言步不计', () => {
    const steps = [
      { stepNo: 1, action: 'assertVisible', status: 'fail' },
      { stepNo: 2, action: 'assertText', status: 'ok' },
      { stepNo: 3, action: 'click', status: 'fail' },
      { stepNo: 4, action: 'assertNotVisible', status: 'fail' },
    ]
    expect(isAssertionStep(steps[0])).toBe(true)
    expect(isAssertionStep(steps[2])).toBe(false)
    expect(countAssertionMismatches(steps)).toBe(2)
  })
})

describe('buildHealSummary：两数并读同行产出，禁单报', () => {
  it('率=命中步/步骤总数（一位小数百分比），失配数同对象返回', () => {
    const steps = [
      { stepNo: 1, action: 'click', status: 'ok' },
      { stepNo: 2, action: 'click', status: 'ok' },
      { stepNo: 3, action: 'assertVisible', status: 'fail' },
      { stepNo: 4, action: 'click', status: 'ok' },
    ]
    const s = buildHealSummary(steps, [row(2)])
    expect(s.healedStepCount).toBe(1)
    expect(s.ledgerStepTotal).toBe(4)
    expect(s.healRate).toBeCloseTo(0.25)
    expect(s.assertionMismatchCount).toBe(1)
  })

  it('分母为零=率诚实 null（禁假 0%/100%），展示位出横杠', () => {
    const s = buildHealSummary([], [row(null)])
    expect(s.healRate).toBe(null)
    expect(formatHealRate(s.healRate)).toBe('-')
    expect(formatHealRate(0.1666)).toBe('16.7%')
  })
})

describe('锚 diff 文案单源：旧锚 → 新锚 同一函数两处消费', () => {
  it('报告展开与库弹窗同字面', () => {
    expect(healAnchorDiffText(row(2))).toBe('xpath: //div[@id="a"] → text: 提交')
  })
})

describe('api 镜像与消费面在位（契约防回归）', () => {
  const api = readFileSync(resolve(here, '../../src/api/webElements.ts'), 'utf-8')
  const report = readFileSync(resolve(here, '../../src/components/ExecTaskResultView.vue'), 'utf-8')
  const ledger = readFileSync(resolve(here, '../../src/views/webelement/WebElementLedgerView.vue'), 'utf-8')

  it('两读门=task 门单页 / element 门带 page+pageSize（有界分页契约）', () => {
    expect(api).toContain("url: '/web-elements/heal-evidence'")
    expect(api).toContain('`/web-elements/${id}/heal-evidence`')
    expect(api).toContain('params: { page, pageSize }')
  })

  it('报告面=徽标列+两数并读行+diff 展开；库面=最近自愈引用读（无第二套 diff 文案）', () => {
    expect(report).toContain('step-heal-badge-')
    expect(report).toContain('exec-heal-summary')
    expect(report).toContain('exec-heal-diff')
    expect(ledger).toContain('ledger-heal-recent')
    expect(report).toContain('healAnchorDiffText')
    expect(ledger).toContain('healAnchorDiffText')
  })
})

describe('locale 双轨键齐且参数名一致（i18n 防半缺）', () => {
  const zh = readFileSync(resolve(here, '../../src/utils/locales/zh-CN.ts'), 'utf-8')
  const en = readFileSync(resolve(here, '../../src/utils/locales/en-US.ts'), 'utf-8')

  for (const key of ['stepHealBadge', 'healSummary', 'healSimilarity', 'healViewport', 'healRevision', 'healElement', 'healOrphanNote']) {
    it(`exec.${key} zh/en 同在`, () => {
      expect(zh).toContain(`${key}:`)
      expect(en).toContain(`${key}:`)
    })
  }
  it('两数并读行含率/触达/失配三插值（禁单报形态）', () => {
    for (const src of [zh, en]) {
      const line = src.split('\n').find((l) => l.trim().startsWith('healSummary:')) ?? ''
      for (const p of ['{rate}', '{healed}', '{total}', '{mismatch}']) {
        expect(line).toContain(p)
      }
    }
  })
  it('屏显概念名=「页面元素库」（WE 命名定版，禁旧词上屏）', () => {
    expect(zh).toContain('明细见页面元素库「最近自愈」')
    expect(en).toContain('Page Element Library')
  })
})

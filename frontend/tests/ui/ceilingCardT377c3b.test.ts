/**
 * CF-1 ceiling card layout contract .
 *
 * The core (PerfLadderCeilingAnalyzer, commit da3990e7) emits report.ceiling ONLY for
 * LADDER runs and honestly omits it for every other pressure model. This contract
 * pins the front mirror of that contract — no second calculation in the page
 * (single-source rule), existence-gated rendering, and the i18n dual track:
 * - api/perf.ts declares PerfCeilingView + ceiling on PerfReportView,
 * - PerfReportView.vue renders the card behind v-if="report.ceiling" (non-LADDER
 * = zero DOM, never an empty shell), with the anchor data-test set,
 * - the tiers table carries border (table baseline) and row tints keyed off the
 * core's breachStep/lastPassStep (mirror, not recompute),
 * - perf.report.ceiling.* key family exists in BOTH locales with identical shape.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'

const SRC = join(__dirname, '..', '..', 'src')
const apiSrc = readFileSync(join(SRC, 'api', 'perf.ts'), 'utf8')
const viewSrc = readFileSync(join(SRC, 'views', 'perf', 'PerfReportView.vue'), 'utf8')

describe('T377 c-3b ceiling card contract', () => {
  it('api mirror declares ceiling on the report view (nullable, honest absence)', () => {
    // Anchor = the field itself (nullable). Later stages appended fields after
    // `ceiling`, so requiring it to be the last member was an over-tight anchor.
    expect(apiSrc).toMatch(/interface PerfReportView \{[\s\S]*?ceiling: PerfCeilingView \| null/)
    expect(apiSrc).toMatch(/export interface PerfCeilingView \{/)
    expect(apiSrc).toMatch(/export interface PerfCeilingTier \{/)
    // Core emits step/nominalRps/passed/breachedGates/requests per tier — mirror all five.
    for (const key of ['step', 'nominalRps', 'passed', 'breachedGates', 'requests']) {
      expect(apiSrc).toContain(`  ${key}:`)
    }
  })

  it('page renders the card only when core sent a ceiling row (no empty shell)', () => {
    expect(viewSrc).toMatch(/v-if="report\.ceiling"[\s\S]*?data-test="perf-ceiling-card"/)
    for (const anchor of ['perf-ceiling-card', 'perf-ceiling-conclusion', 'perf-ceiling-knee',
      'perf-ceiling-table', 'perf-ceiling-caliber']) {
      expect(viewSrc).toContain(`data-test="${anchor}"`)
    }
    // Tier rows render only when applicable — a not-applicable finding shows the
    // honest conclusion/caliber lines, not a fabricated empty table.
    expect(viewSrc).toMatch(/v-if="report\.ceiling\.applicable && report\.ceiling\.tiers\.length"/)
  })

  it('tiers table keeps the table baseline and mirrors core indices (no recompute)', () => {
    const tableBlock = /<el-table\n?[^]*?data-test="perf-ceiling-table"[\s\S]*?<\/el-table>/.exec(viewSrc)
      ?? /<el-table[^]*?data-test="perf-ceiling-table"[\s\S]*?<\/el-table>/.exec(viewSrc)
    expect(tableBlock, 'ceiling tiers table must exist').not.toBeNull()
    expect(tableBlock![0]).toContain('border')
    // The page must not re-derive watermark/breach/knee math — only the core fields.
    expect(viewSrc).not.toMatch(/safeWatermarkRps\s*[*×]/)
    expect(viewSrc).toMatch(/c\.breachStep === row\.step/)
    expect(viewSrc).toMatch(/c\.lastPassStep === row\.step/)
  })

  it('i18n dual track carries the perf.report.ceiling family with identical shape', () => {
    const zh = (zhCN as Record<string, any>).perf?.report?.ceiling
    const en = (enUS as Record<string, any>).perf?.report?.ceiling
    expect(zh, 'zh-CN must carry perf.report.ceiling').toBeTruthy()
    expect(en, 'en-US must carry perf.report.ceiling').toBeTruthy()
    const flat = (o: Record<string, unknown>, p = ''): string[] =>
      Object.entries(o).flatMap(([k, v]) =>
        typeof v === 'object' && v !== null ? flat(v as Record<string, unknown>, `${p}${k}.`) : [`${p}${k}`])
    expect(flat(en)).toEqual(flat(zh))
    for (const key of ['title', 'knee', 'pass', 'fail', 'col.step', 'col.nominalRps',
      'col.verdict', 'col.gates', 'col.requests']) {
      expect(zh, `missing zh key ${key}`).toBeTruthy()
    }
  })
})

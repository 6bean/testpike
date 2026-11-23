/**
 * GB-4 knife 2 : the report page
 * renders the server-produced guide as a first-class block — mode badge,
 * section groups with item lists, one boundary note — and nothing else.
 *
 * The layout contract pins the single-source discipline: items/sections text
 * come from the backend (report.residualGuide); the page never reassembles
 * guide wording locally, never renders an empty shell when the guide is null
 * (honest absence), and mirrors the wire shape on the api layer.
 *
 * Posture follows perfReportLayering.test.ts (?raw mirror assertions).
 */
import { describe, expect, it } from 'vitest'
import reportRaw from '@/views/perf/PerfReportView.vue?raw'
import perfApiRaw from '@/api/perf.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('residual guide block: consumes report.residualGuide single-source', () => {
  it('the api mirror types the guide wire shape (mode/sections/boundaryNote, nullable)', () => {
    expect(perfApiRaw).toMatch(/residualGuide:\s*PerfResidualGuideView\s*\|\s*null/)
    expect(perfApiRaw).toMatch(/interface PerfResidualGuideView \{/)
    expect(perfApiRaw).toMatch(/mode: string/)
    expect(perfApiRaw).toMatch(/sections: PerfResidualGuideSection\[\]/)
    expect(perfApiRaw).toMatch(/boundaryNote: string/)
  })
  it('the block renders only when the guide exists (null = zero DOM, no shell)', () => {
    expect(reportRaw).toContain('v-if="report.residualGuide"')
    expect(reportRaw).toContain('data-test="perf-residual-guide"')
  })
  it('the mode badge reuses the existing isolation label spectrum (no second wording)', () => {
    expect(reportRaw).toMatch(/perf\.isolation\.\$\{report\.residualGuide\.mode\}/)
    expect(reportRaw).toContain('data-test="perf-residual-mode"')
  })
  it('vision adoption: the mode badge sits on the title row (head wrapper, tag after h4)', () => {
    const head = reportRaw.slice(reportRaw.indexOf('perf-report__residual-head'))
    const headBlock = head.slice(0, head.indexOf('</div>'))
    expect(headBlock).toContain('perf.report.residualGuideTitle')
    expect(headBlock).toContain('perf-residual-mode')
    expect(reportRaw).toMatch(/\.perf-report__residual-head \{[^}]*display: flex/)
  })
  it('sections render title + item list straight from the payload (no local assembly)', () => {
    expect(reportRaw).toMatch(/v-for="\(sec, i\) in report\.residualGuide\.sections"/)
    expect(reportRaw).toMatch(/sec\.items/)
    expect(reportRaw).toContain('data-test="perf-residual-section"')
    expect(reportRaw).toContain('data-test="perf-residual-boundary"')
  })
})

describe('i18n dual-track: only block-level titles are locale keys, no guide prose', () => {
  it('zh carries the block title key', () => {
    expect(zhRaw).toMatch(/residualGuideTitle: '残留巡检引导'/)
  })
  it('en carries the same key (dual-track, no hard-coded Chinese in the page)', () => {
    expect(enRaw).toMatch(/residualGuideTitle: 'Residual-cleanup guide'/)
  })
  it('the page never hard-codes section prose (backend-owned text only)', () => {
    const block = reportRaw.slice(reportRaw.indexOf('perf-residual-guide'))
    expect(block.slice(0, 1200)).not.toMatch(/[\u4e00-\u9fff]/)
  })
})

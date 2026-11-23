/**
 * (B-11): the report face gains the AI run-insight door — one
 * click spends exactly one model hop and the read-out lands as an in-page
 * tinted card (never a dialog), while every refusal shape (closed switch,
 * non-terminal run, foreign project) rides through untranslated from core.
 *
 * Contract asserted here:
 * 1. the api layer mirrors the admin door (POST /perf/runs/{id}/insight with
 * projectId as query) and the {runId, verdict, verdictPhrase, insight}
 * reply shape the core orchestration assembles;
 * 2. the report view consumes it through the api mirror only — no second
 * request path, no recomputed wording (red line 6);
 * 3. the cost door guards the double-click: loading + disabled while in
 * flight (R22 discipline — one click ships exactly one model hop);
 * 4. the read-out shows as a tinted block inside the report, never a dialog
 * or message box (a tinted in-page band), and the pending shape displays
 * the judge's own phrase instead of an empty success card;
 * 5. refusals show the backend message verbatim (bizErrorMsg pass-through —
 * no second copy of the gate wording in the browser);
 * 6. every new string lives in both locales (i18n dual-track, zero hard CJK
 * in the view).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/perf.ts?raw'
import reportRaw from '@/views/perf/PerfReportView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('perf api contract mirror (AI run insight)', () => {
  it('exports requestPerfRunInsight against POST /perf/runs/{id}/insight', () => {
    expect(apiRaw).toMatch(/export function requestPerfRunInsight\(/)
    expect(apiRaw).toMatch(/url: `\/perf\/runs\/\$\{runId\}\/insight`, method: 'post'/)
  })

  it('mirrors the reply shape the core orchestration assembles', () => {
    expect(apiRaw).toMatch(
      /interface PerfRunInsightView \{[\s\S]*?runId:[\s\S]*?verdict:[\s\S]*?verdictPhrase:[\s\S]*?insight:[\s\S]*?\}/)
  })
})

describe('report AI insight door (B-11 slice 3c)', () => {
  it('consumes the door through the api mirror only', () => {
    expect(reportRaw).toMatch(/requestPerfRunInsight/)
    expect(reportRaw).not.toMatch(/\/insight/) // no direct request path in the view
  })

  it('the cost door is double-click safe: loading + disabled while in flight', () => {
    expect(reportRaw).toMatch(/data-test="perf-report-insight-btn"/)
    const btn = reportRaw.slice(reportRaw.indexOf('data-test="perf-report-insight-btn"') - 260,
      reportRaw.indexOf('data-test="perf-report-insight-btn"') + 260)
    expect(btn).toMatch(/:loading="insighting"/)
    expect(btn).toMatch(/:disabled="insighting"/)
  })

  it('the read-out is an in-page tinted card, never a dialog', () => {
    expect(reportRaw).toMatch(/data-test="perf-report-insight-card"/)
    expect(reportRaw).toMatch(/data-test="perf-report-insight-text"/)
    expect(reportRaw).not.toMatch(/ElMessageBox/)
    expect(reportRaw).not.toMatch(/<el-dialog/)
    // tint comes from a theme token (dark/light both follow the shell), not a magic color
    const style = reportRaw.slice(reportRaw.indexOf('.perf-report__insight {'))
    expect(style.slice(0, 160)).toMatch(/var\(--el-/)
  })

  it('band title -> body gap sits on the 4px grid (vision close-out fix)', () => {
    // The vision feed caught an off-grid 6px magic number here;
    // the live ledger measured 6px before the fix and 8px after. Lock the
    // grid so the squeeze cannot come back.
    const rule = reportRaw.match(/\.perf-report__insight \.perf-report__subtitle \{ margin: 0 0 (\d+)px/)
    expect(rule).not.toBeNull()
    const px = Number(rule?.[1])
    expect(px % 4).toBe(0)
  })

  it('the pending shape shows the judge phrase, not an empty success card', () => {
    const block = reportRaw.slice(reportRaw.indexOf('data-test="perf-report-insight-card"'),
      reportRaw.indexOf('data-test="perf-report-insight-card"') + 900)
    expect(block).toMatch(/insight\.insight/)
    expect(block).toMatch(/data-test="perf-report-insight-pending"/)
    expect(block).toMatch(/insight\.verdictPhrase/)
  })

  it('refusals ride through untranslated — no second copy of gate wording', () => {
    const fn = reportRaw.slice(reportRaw.indexOf('async function fetchInsight'))
    expect(fn).toMatch(/bizErrorMsg/)
    expect(reportRaw).not.toMatch(/未开启|当前未开启/)
  })
})

describe('i18n dual-track', () => {
  it('both locales carry the new keys', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toMatch(/insightGo: '/)
      expect(raw).toMatch(/insightTitle: '/)
      expect(raw).toMatch(/insightCostHint: '/)
      expect(raw).toMatch(/insightFail: '/)
    }
  })
})

/**
 * Scenario trend panel in the perf workbench ( stage c frontend half,
 * contract first).
 *
 * Backend half is shipped: GET /perf/scenarios/{id}/trend returns ascending
 * terminal-run rows (whole-run percentiles from merged histogram buckets,
 * honest worst-second fallback when a legacy run has no buckets, window bound
 * = hot-updatable K8 key perf.trend.max-runs default 200).
 *
 * Contract asserted here:
 * 1. the row ledger gains a trend entry (data-test) opening a drawer that
 * consumes the trend door through the perf api layer (no fetch forked in
 * the view);
 * 2. chart geometry reuses chartUtils (CHART_W/buildPolySegments/scaleX/
 * scaleY) — the single SVG piece shared with the run detail curves, no
 * second chart helper (red line: no duplicated logic);
 * 3. display scale: the row ledger renders through useWindowedList (pad
 * spacers + visible window), so a 200-run window never paints 200 DOM
 * rows;
 * 4. caliber honesty: the machine enums (BUCKET/FALLBACK/WEIGHTED/MEAN) are
 * mapped through i18n display wording, never rendered raw (screen-text
 * iron law: no internal codes on the face);
 * 5. page state: the open drawer mirrors ?trend=<id> so reload restores it;
 * 6. i18n dual track: zh + en carry the whole trend key family;
 * 7. mark badges ride the GOLDEN/CANDIDATE/VOID display keys only.
 */
import { describe, expect, it } from 'vitest'
import viewRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import apiRaw from '@/api/perf.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T378 scenario trend panel contract (perf workbench)', () => {
  it('row action opens the trend drawer through the api door (no fetch fork)', () => {
    expect(viewRaw).toContain('data-test="perf-row-trend"')
    expect(viewRaw).toContain('data-test="perf-trend-drawer"')
    expect(viewRaw).toContain('getPerfScenarioTrend')
    expect(apiRaw).toContain('/trend')
    expect(apiRaw).toMatch(/url: *`\/perf\/scenarios\/\$\{scenarioId\}\/trend`/)
  })

  it('chart geometry delegates to the shared chartUtils piece', () => {
    expect(viewRaw).toMatch(/import \{[^}]*buildPolySegments[^}]*\} from '\.\/chartUtils'/)
    expect(viewRaw).toMatch(/import \{[^}]*scaleX[^}]*scaleY|import \{[^}]*scaleY[^}]*scaleX/)
    expect(viewRaw).toContain('maxOf')
    // the trend svg uses the shared viewBox geometry constants
    expect(viewRaw).toContain('${CHART_W} ${CHART_H}')
  })

  it('the row ledger is windowed, never a flat v-for over the full window', () => {
    expect(viewRaw).toContain('useWindowedList')
    expect(viewRaw).toContain('trendList.visible.value')
    expect(viewRaw).toContain('trendList.padTop.value')
    expect(viewRaw).toContain('trendList.padBottom.value')
    expect(viewRaw).toContain('@scroll="trendList.sync()"')
    // ledger reads newest-first (runs-drawer posture) while the chart keeps
    // the core ascending array for a left-to-right time axis
    expect(viewRaw).toContain('trendLedger')
  })

  it('caliber enums never reach the screen raw — display wording via i18n', () => {
    // the row badge and tooltip go through the mapping functions only
    expect(viewRaw).toContain('trendCaliberShort(r)')
    expect(viewRaw).toContain('trendCaliberNote(r)')
    // no interpolation that would print the enum value itself
    expect(viewRaw).not.toMatch(/\{\{\s*r\.quantileCaliber\s*\}\}/)
    expect(viewRaw).not.toMatch(/\{\{\s*r\.errorCaliber\s*\}\}/)
  })

  it('open state mirrors the URL query (?trend=ID) for reload restore', () => {
    expect(viewRaw).toContain('trend: String(sc.id)')
    expect(viewRaw).toMatch(/q\.trend/)
    expect(viewRaw).toContain('openTrendById')
  })

  it('project slice switch closes the panel (no cross-slice rows)', () => {
    const w = viewRaw.slice(viewRaw.indexOf('watch(followTick'))
    expect(w.slice(0, 300)).toContain('trendOpen.value = false')
  })

  it('golden/candidate/void badges map through perf.mark display keys', () => {
    expect(viewRaw).toContain('perf.mark.')
    const zh = zhRaw.slice(zhRaw.indexOf('  perf: {'))
    expect(zh).toContain("GOLDEN: '金跑'")
  })

  it('i18n dual track: both locales carry the trend key family', () => {
    for (const key of ['trendTitle', 'trendEmpty', 'trendChart', 'trendLoadFail', 'caliberFull', 'caliberFallback', 'noteBucket', 'noteFallback', 'noteWeighted', 'noteMean']) {
      expect(zhRaw).toContain(key)
      expect(enRaw).toContain(key)
    }
    expect(zhRaw).toMatch(/trend: \{/)
    expect(enRaw).toMatch(/trend: \{/)
    // action word present on both sides
    expect(zhRaw).toContain("trend: '趋势'")
    expect(enRaw).toContain("trend: 'Trend'")
  })

  it('terminal rows carry the run link with projectId query like the runs drawer', () => {
    expect(viewRaw).toMatch(/\/perf\/run\/\$\{r\.runId\}/)
  })
})

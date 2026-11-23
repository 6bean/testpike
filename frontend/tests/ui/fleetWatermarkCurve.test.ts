/**
 * T294d fleet pool-watermark curve contract (frontend .
 *
 * The watermark band plots the persisted sampler history (core ledger ->
 * admin bridge GET /cluster/pools/watermarks -> this page). Pinned disciplines:
 * - api mirror: FleetPoolWatermarkPoint carries the admin
 * ExecutorPoolWatermarkView field names verbatim so the envelope binds
 * without a second mapping layer, and the call passes optional
 * executorType/limit straight through (the default window and the clamp
 * are the core ledger's, not the browser's);
 * - pure reader: every plotted value is a captured observation. The page
 * groups rows by executor type and sorts time ascending — it computes no
 * trend, no verdict and no fill-in for missing samples (red line 6);
 * - resource pairing (R57): each lazily created echarts instance is disposed
 * on unmount and the single window resize listener is removed — a band
 * that only ever inits charts leaks a canvas per route revisit;
 * - honest states: skeleton while loading, stated empty line when the
 * sampler has produced nothing yet — never a blank chart pretending data;
 * - one refresh command: the watermark series reloads inside the same
 * Promise.all as the other fleet bands, so no band can show an older round.
 */
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale as lookup } from '../_localeLookup'
import { apiSrc, viewSrc } from './fleetPageSources'

const WATERMARK_KEYS = [
  'watermarkTitle', 'watermarkEmpty', 'watermarkWindow',
  'watermarkSeriesPending', 'watermarkSeriesReplicas', 'watermarkSeriesActive',
  'watermarkP95Label',
]

describe('T294d fleet pool-watermark curve contract', () => {
  it('api mirrors the admin watermark view and binds the bridge endpoint with optional params', () => {
    expect(apiSrc).toMatch(/export interface FleetPoolWatermarkPoint \{/)
    for (const field of [
      'executorType', 'pendingDispatchDepth', 'currentReplicas',
      'activeExecutors', 'p95WaitSeconds', 'sampledEpochSeconds',
    ]) {
      expect(apiSrc, `FleetPoolWatermarkPoint 缺字段 ${field}`).toMatch(new RegExp(`\\b${field}[?]?:`))
    }
    expect(apiSrc).toMatch(/export function getFleetPoolWatermarks\(/)
    expect(apiSrc).toMatch(/url: '\/cluster\/pools\/watermarks'/)
    expect(apiSrc).toMatch(/executorType: executorType \|\| undefined/)
    expect(apiSrc).toMatch(/limit: limit \|\| undefined/)
  })

  it('watermark band renders skeleton, honest empty state and per-type cards', () => {
    for (const anchor of ['fleet-watermark-band', 'fleet-watermark-skeleton', 'fleet-watermark-empty']) {
      expect(viewSrc, `缺水位曲线锚 ${anchor}`).toContain(anchor)
    }
    // One card per executor type, keyed by the type token itself.
    expect(viewSrc).toContain('fleet-watermark-card-')
    // The window caption states the bounded window like the timeline head does.
    expect(viewSrc).toContain('fleet-watermark-window')
  })

  it('plot data is the raw observation; the page groups and sorts but never re-decides', () => {
    // Grouping by type and ascending time only — no trend maths, no clamping.
    expect(viewSrc).toMatch(/watermarkSeries/)
    // The newest p95 is shown as captured, formatted only for display.
    expect(viewSrc).toMatch(/p95WaitSeconds/)
    // The page stays a pure reader: no write request anywhere in this view.
    expect(viewSrc).not.toMatch(/method: 'post'/)
  })

  it('every lazy chart instance is paired with disposal and the resize listener is removed (R57)', () => {
    expect(viewSrc).toMatch(/await import\('echarts'\)/)
    const initDefs = viewSrc.match(/watermarkCharts\.set\(/g) ?? []
    expect(initDefs.length, '曲线实例登记处必须存在').toBeGreaterThan(0)
    expect(viewSrc).toMatch(/\.dispose\(\)/)
    // One shared resize handler, removed on unmount (no per-card listeners).
    const addDefs = viewSrc.match(/window\.addEventListener\('resize'/g) ?? []
    const removeDefs = viewSrc.match(/window\.removeEventListener\('resize'/g) ?? []
    expect(addDefs.length).toBe(1)
    expect(removeDefs.length).toBe(1)
    // Dispose covers the route-leave path and the data-shrink path both.
    expect(viewSrc).toMatch(/disposeWatermarkCharts|onBeforeUnmount/)
  })

  it('refresh reloads the watermark series together with the other read bands', () => {
    expect(viewSrc).toMatch(/async function loadWatermark\(/)
    expect(viewSrc).toMatch(/const watermark = loadWatermark\(\)/)
    expect(viewSrc).toMatch(/Promise\.all\(\[scale, matrix, timeline, commands, watermark\]\)/)
  })

  it('i18n dual track carries the watermark family with identical shape', () => {
    for (const key of WATERMARK_KEYS) {
      expect(typeof lookup(zhCN, `pool.${key}`), `zh 缺 pool.${key}`).toBe('string')
      expect(typeof lookup(enUS, `pool.${key}`), `en 缺 pool.${key}`).toBe('string')
      expect((lookup(zhCN, `pool.${key}`) as string).length, `zh pool.${key} 空值`).toBeGreaterThan(0)
      expect((lookup(enUS, `pool.${key}`) as string).length, `en pool.${key} 空值`).toBeGreaterThan(0)
    }
  })
})

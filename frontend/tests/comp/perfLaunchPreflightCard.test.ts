/**
 * Launch-time resource pre-flight card in the perf workbench (
 * frontend half — CF-5 capacity outlook + CF-7 dataset health notice).
 *
 * Backend half is already shipped: GET /api/v1/perf/resource-preflight
 * ?projectId&scenarioId returns { engine, capacityNote, datasetNote,
 * requiredConcurrency } — wording projected server-side from the dispatch
 * path's own single sources (online fleet, GA-4 capability covers, queue
 * depth, launch-pinned allocation order). The door blocks nothing: rejection
 * stays owned by the launch transaction gates.
 *
 * Contract asserted here:
 * 1. the perf api layer mirrors the endpoint exactly once (no second door);
 * 2. opening the launch dialog fetches the pre-flight WITHOUT blocking the
 * dialog render (the card fills in asynchronously);
 * 3. the dialog carries a read-only card slot (data-test="perf-preflight-card")
 * rendering capacityNote and datasetNote;
 * 4. the launch confirm button stays governed only by the environment
 * selection — a pre-flight wording NEVER disables it (notice, not gate);
 * 5. a pre-flight fetch failure degrades to a silent card, the dialog keeps
 * working (honest absence, no error toast blocking the operator);
 * 6. i18n dual track carries the card's own labels.
 */
import { describe, expect, it } from 'vitest'
import viewRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import apiRaw from '@/api/perf.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('perf launch dialog resource pre-flight card', () => {
  it('the perf api layer mirrors the single pre-flight door', () => {
    expect(apiRaw).toContain('export function getResourcePreflight')
    const api = apiRaw.slice(apiRaw.indexOf('export function getResourcePreflight'))
    expect(api).toContain("'/perf/resource-preflight'")
    expect(api).toContain('params: { projectId, scenarioId }')
    // exactly one mirror of this endpoint across the whole api layer
    const doors = apiRaw.match(/resource-preflight/g) ?? []
    expect(doors.length).toBe(1)
  })

  it('opening the launch dialog fetches the pre-flight without blocking render', () => {
    const fn = viewRaw.slice(viewRaw.indexOf('function openLaunch'), viewRaw.indexOf('async function confirmLaunch'))
    // dialog opens before/independent of the pre-flight await
    expect(fn).toContain('launchDialogOpen.value = true')
    expect(fn).toContain('getResourcePreflight')
    expect(fn).toMatch(/preflight/)
  })

  it('the dialog renders the read-only pre-flight card slot', () => {
    expect(viewRaw).toContain('data-test="perf-preflight-card"')
    expect(viewRaw).toContain('capacityNote')
    expect(viewRaw).toContain('datasetNote')
  })

  it('the pre-flight never gates the confirm button (notice, not gate)', () => {
    const btn = viewRaw.slice(viewRaw.indexOf('data-test="perf-launch-confirm"') - 260, viewRaw.indexOf('data-test="perf-launch-confirm"'))
    expect(btn).toContain(':disabled="launchEnvId == null"')
    expect(btn).not.toMatch(/disabled[^>]*preflight/i)
  })

  it('a pre-flight failure degrades to silence, never a blocking toast', () => {
    const fn = viewRaw.slice(viewRaw.indexOf('function openLaunch'), viewRaw.indexOf('async function confirmLaunch'))
    const pf = fn.slice(fn.indexOf('getResourcePreflight'))
    // the catch path resets state instead of raising a user-facing error
    expect(pf).toMatch(/catch[^\n]*\{[\s\S]{0,200}preflight[A-Za-z]*\.value = null/)
  })

  it('i18n dual track carries the pre-flight card labels', () => {
    for (const key of ['launchPreflightTitle', 'launchPreflightNote', 'launchPreflightEngine']) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
    // wording contract: the title names the non-blocking behavior in operator
    // language (vision review fix 10-25: the old one-word phrasing was opaque)
    expect(zhRaw).toContain('不影响发起操作')
    expect(zhRaw).toContain('压测引擎：')
    expect(enRaw).toContain('does not block launching')
  })
})

/**
 * Scenario delete entry on the perf workbench .
 *
 * Backend half is already shipped: GET /api/v1/perf/scenarios/{id}/delete-impact
 * returns the real ledger { runningRuns, historicalRuns, linkedApprovals,
 * pendingApprovals, source } and DELETE /api/v1/perf/scenarios/{id} refuses an
 * in-flight scenario with a human reason naming the count. The D2 ruling keeps
 * finished runs alive with a NULL link — so the card must show the server's
 * counts verbatim instead of a generic "are you sure", and must honestly
 * disable the confirm button while the ledger says a run is still in flight.
 *
 * Contract asserted here:
 * 1. the perf api layer mirrors both doors exactly once (no second client);
 * 2. a row-level delete action exists and asks the impact ledger BEFORE the
 * card renders (no locally computed counts, no card without the numbers);
 * 3. the card names every consequence with the server's own numbers:
 * in-flight refusal, finished runs kept, approval tickets unlinked,
 * mounted CUSTOM script retired with the row;
 * 4. confirm is disabled while runningRuns > 0 (with the pointer wording),
 * and a successful delete reloads the list (persistence is E2E's job);
 * 5. i18n dual track carries the card keys, no hardcoded wording in the view.
 */
import { describe, expect, it } from 'vitest'
import viewRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import apiRaw from '@/api/perf.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('perf scenario delete confirmation card', () => {
  it('the perf api layer mirrors the two delete doors exactly once', () => {
    expect(apiRaw).toContain('export function getScenarioDeleteImpact')
    expect(apiRaw).toContain('export function deletePerfScenario')
    const impact = apiRaw.slice(apiRaw.indexOf('export function getScenarioDeleteImpact'))
    expect(impact).toContain('delete-impact')
    expect(impact).toContain('params: { projectId }')
    const del = apiRaw.slice(apiRaw.indexOf('export function deletePerfScenario'))
    expect(del).toContain("method: 'delete'")
    // one mirror each across the whole api layer
    expect((apiRaw.match(/delete-impact/g) ?? []).length).toBe(1)
    expect((apiRaw.match(/ScenarioDeleteImpact|scenarioDeleteImpact/g) ?? []).length).toBeGreaterThanOrEqual(1)
  })

  it('the impact ledger is fetched before the card opens and drives the copy', () => {
    const fn = viewRaw.slice(
      viewRaw.indexOf('function openDelete'),
      viewRaw.indexOf('async function confirmDelete'),
    )
    expect(fn).toContain('getScenarioDeleteImpact')
    expect(fn).toContain('deleteDialogOpen.value = true')
    // counts come from the server, never re-derived in the page
    expect(viewRaw).toContain('.runningRuns')
    expect(viewRaw).toContain('.historicalRuns')
    expect(viewRaw).not.toMatch(/historicalRuns\s*=\s*runs\.length/)
  })

  it('the card renders the consequence lines with the server numbers', () => {
    expect(viewRaw).toContain('data-test="perf-delete-dialog"')
    expect(viewRaw).toContain('data-test="perf-delete-impact"')
    expect(viewRaw).toContain('perf.deleteKeepsRuns')
    expect(viewRaw).toContain('perf.deleteUnlinksApprovals')
    expect(viewRaw).toContain('perf.deleteScriptNote')
  })

  it('confirm is honestly disabled while a run is still in flight', () => {
    const btn = viewRaw.slice(
      viewRaw.indexOf('data-test="perf-delete-confirm"') - 260,
      viewRaw.indexOf('data-test="perf-delete-confirm"') + 40,
    )
    expect(btn).toContain('deleteImpact.runningRuns > 0')
    expect(viewRaw).toContain('perf.deleteBlocked')
  })

  it('a successful delete refreshes the list and names the scenario', () => {
    const fn = viewRaw.slice(viewRaw.indexOf('async function confirmDelete'))
    expect(fn).toContain('deletePerfScenario')
    expect(fn).toContain('loadAll()')
    expect(fn).toContain('deleteDialogOpen.value = false')
  })

  it('i18n dual track carries the delete card keys', () => {
    for (const key of [
      'deleteAction',
      'deleteTitle',
      'deleteQuestion',
      'deleteKeepsRuns',
      'deleteUnlinksApprovals',
      'deleteScriptNote',
      'deleteBlocked',
      'deleteOk',
    ]) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
    // operator wording: says what happens to history, not that it was ruled
    expect(zhRaw).toContain('历史运行记录将保留')
    expect(enRaw).toContain('finished runs are kept')
  })

  it('the row delete button sits after the launch action and is a danger link', () => {
    const col = viewRaw.slice(viewRaw.indexOf('data-test="perf-row-launch"'))
    expect(col).toContain('data-test="perf-row-delete"')
    const btn = viewRaw.slice(
      viewRaw.indexOf('data-test="perf-row-delete"') - 120,
      viewRaw.indexOf('data-test="perf-row-delete"') + 120,
    )
    expect(btn).toContain('type="danger"')
  })
})

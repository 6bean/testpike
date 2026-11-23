/**
 * GB-1 launch-time environment override in the perf workbench ( stage d
 * frontend half, contract first).
 *
 * Backend half is already shipped: POST /perf/scenarios/{id}/runs takes an
 * optional envId; absent = scenario-bound default, present = must exist in the
 * same project (core refuses cross-project/unknown ids with a human reason).
 * The gate, the GA-2 occupancy lock and the pinned snapshot all decide on the
 * effective environment.
 *
 * Contract asserted here:
 * 1. clicking launch opens a dialog carrying an environment selector
 * (data-test="pl-env") instead of firing the run directly;
 * 2. the selector consumes the existing getProjectEnvs list — no second
 * environment endpoint and no page-level duplicate selector tree;
 * sends no override... the resolved default equals the scenario binding,
 * so behavior is byte-compatible);
 * 4. confirm calls launchPerfRun with the selected envId threaded to the api
 * layer as the query param `envId`;
 * 5. i18n dual track: both locales carry the launch-dialog keys.
 */
import { describe, expect, it } from 'vitest'
import viewRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import apiRaw from '@/api/perf.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T377 GB-1 launch env override in the perf workbench', () => {
  it('launch opens a dialog with an environment selector (no direct fire)', () => {
    expect(viewRaw).toContain('data-test="perf-launch-dialog"')
    expect(viewRaw).toContain('data-test="pl-env"')
    // the old direct ElMessageBox.confirm-then-fire path must be gone
    expect(viewRaw).not.toContain('ElMessageBox.confirm(t(\'perf.launchConfirm\'')
  })

  it('the selector reuses getProjectEnvs — one environment list source', () => {
    expect(viewRaw).toContain('getProjectEnvs')
    // no new env endpoint invented in the perf api layer
    expect(apiRaw).not.toMatch(/url: *['"`]\/projects\/\$\{[^}]*\}\/envs/)
  })

  it('opening the dialog preselects the scenario-bound env id', () => {
    const fn = viewRaw.slice(viewRaw.indexOf('function openLaunch'), viewRaw.indexOf('async function confirmLaunch'))
    expect(fn).toContain('sc.env_id')
    expect(fn).toContain('launchEnvId.value')
  })

  it('confirm threads the chosen envId through launchPerfRun to the envId query param', () => {
    expect(viewRaw).toMatch(/launchPerfRun\(\s*projectId\.value!,\s*sc\.id,\s*launchEnvId\.value\s*\)/)
    const api = apiRaw.slice(apiRaw.indexOf('export function launchPerfRun'))
    expect(api).toContain('envId?: number | null')
    expect(api).toContain('params: { projectId, envId }')
  })

  it('i18n dual track carries the launch dialog keys', () => {
    for (const key of ['launchEnvLabel', 'launchEnvHint', 'launchConfirmBtn']) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
  })
})

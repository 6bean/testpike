/**
 * scheduled-firing wizard section (layout contract, RED first).
 *
 * Background: the save chain already carries scheduleCron / scheduleEnabled /
 * scheduleEnvId and the next-fire preview door is live
 * (GET /api/v1/schedule/cron-preview, 1) — but the wizard had no
 * surface to declare any of it, so the promised capability was unreachable
 * from the UI. This contract pins the wiring:
 * 1. api/schedule.ts exposes previewCron against /schedule/cron-preview
 * (client mirrors the admin door, no recompute — red line 6);
 * 2. the wizard renders the schedule block: enable switch, cron input,
 * pinned-environment select, preview action, preview output area;
 * 3. payload round-trip: buildPayload sends the three schedule fields and
 * openWith hydrates them from the scenario row (edit never drops them);
 * 4. step-flow guidance mirrors the server gate: enabling without a cron is
 * blocked before submit (same rule as core validateSchedule);
 * 5. i18n dual track: every new key exists in both zh-CN and en-US.
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import scheduleApiRaw from '@/api/schedule.ts?raw'
import perfApiRaw from '@/api/perf.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('perf wizard scheduled-firing section', () => {
  it('api/schedule.ts previews cron fires through the admin door', () => {
    expect(scheduleApiRaw).toMatch(/export function previewCron\(/)
    expect(scheduleApiRaw).toContain('/schedule/cron-preview')
  })

  it('the wizard renders the schedule block with stable anchors', () => {
    for (const anchor of [
      'data-test="pw-schedule-switch"',
      'data-test="pw-schedule-cron"',
      'data-test="pw-schedule-env"',
      'data-test="pw-schedule-preview"',
      'data-test="pw-schedule-fires"',
    ]) {
      expect(wizardRaw).toContain(anchor)
    }
  })

  it('the preview action consumes previewCron without recomputing locally', () => {
    expect(wizardRaw).toContain('previewCron(')
    // results come back as server instants rendered as-is (single source)
    expect(wizardRaw).toMatch(/function previewScheduleFires|const previewScheduleFires/)
  })

  it('payload and edit round-trip carry the three schedule fields', () => {
    expect(wizardRaw).toContain('scheduleCron:')
    expect(wizardRaw).toContain('scheduleEnabled:')
    expect(wizardRaw).toContain('scheduleEnvId:')
    expect(wizardRaw).toContain('sc.schedule_cron')
    expect(wizardRaw).toContain('sc.schedule_enabled')
    expect(wizardRaw).toContain('sc.schedule_env_id')
    // the save payload mirror declares them too
    expect(perfApiRaw).toContain('scheduleCron?:')
    expect(perfApiRaw).toContain('scheduleEnvId?:')
  })

  it('enabling the switch without a cron blocks before submit (server-rule mirror)', () => {
    expect(wizardRaw).toContain('scheduleNeedCron')
    // step guidance must scan the schedule step too
    expect(wizardRaw).toMatch(/for \(let s = 0; s <= 2; s\+\+\)/)
  })

  it('i18n dual track covers every new schedule key', () => {
    for (const key of [
      'scheduleSection',
      'scheduleEnabledLabel',
      'scheduleCron',
      'scheduleCronPh',
      'scheduleEnv',
      'scheduleEnvHint',
      'schedulePreview',
      'schedulePreviewFail',
      'scheduleFiresEmpty',
      'scheduleNeedCron',
    ]) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
  })
})

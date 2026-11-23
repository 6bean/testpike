/**
 * slice-e2 (D8) approval-linked auto-launch — frontend contract (RED first).
 *
 * The backend half lives in core (PerfApprovalAutoLaunchTest + the V285 columns);
 * this contract pins the frontend wiring:
 * 1. api/perf.ts mirrors the D8 payload leg (scenarioId on the apply payload) and
 * the two read-back columns on the approval row (scenario_id / auto_launch_note);
 * 2. the wizard submits the persisted scenario id when there is one (edit lane) and
 * honestly omits it otherwise (never a dangling id);
 * 3. the approval dialog explains the link + the switch in plain words (the row only
 * appears when a scenario can actually be linked);
 * 4. i18n dual-track: the hint key exists in both locale files, zero hard-coded
 * Chinese in the vue source for this line.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/perf.ts?raw'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T378 e2 D8 approval auto-launch frontend contract', () => {
  it('api layer mirrors the D8 payload and read-back columns', () => {
    expect(apiRaw).toContain('scenarioId?: number | null')
    expect(apiRaw).toContain('scenario_id?: number | null')
    expect(apiRaw).toContain('auto_launch_note?: string | null')
  })

  it('wizard submits the persisted scenario id, honest absence otherwise', () => {
    expect(wizardRaw).toContain('scenarioId: editingId.value ?? null')
  })

  it('approval dialog carries the plain-words link hint (edit lane only)', () => {
    expect(wizardRaw).toContain('data-test="pw-ap-scenario-link"')
    expect(wizardRaw).toContain("t('perf.applied.scenarioLink')")
    // the hint row is gated on a persisted scenario (no dangling reference shown)
    expect(wizardRaw).toMatch(/v-if="editingId"[\s\S]{0,120}pw-ap-scenario-link/)
  })

  it('i18n dual-track ships the hint key in both locales', () => {
    expect(zhRaw).toContain("scenarioLink: '")
    expect(enRaw).toContain('scenarioLink: \'')
  })
})

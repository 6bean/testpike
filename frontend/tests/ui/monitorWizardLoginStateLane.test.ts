/**
 * Monitor wizard login-state lane contract (vitest node env, source + locale scan).
 *
 * The wizard offers the login-state direct read shape, so it has to consume the
 * ledger lane through the pieces that already own it:
 *   - the vault-reference key names come from utils/loginProbeForm (core mirror),
 *   - the "which ledger rows can answer a request header" verdict comes from
 *     utils/authForm.isConsumableSessionState, which is the same predicate the
 *     server-side header builder uses.
 * A second copy of either would let the picker and the backend disagree about what
 * is selectable, which shows up as a row the user picks and the probe then refuses.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const WIZARD = readFileSync(
  fileURLToPath(new URL('../../src/views/monitor/TargetCreateWizard.vue', import.meta.url)),
  'utf-8',
)

/** Locale keys the direct-read lane needs; both languages must carry them. */
const NEW_WIZARD_KEYS = [
  'probeMode',
  'modeReplay',
  'modeStateRead',
  'modeHint',
  'stateName',
  'stateNamePh',
  'stateNameHint',
  'checkUrl',
  'checkUrlPh',
  'checkUrlHint',
  'needStateName',
  'needCheckUrl',
]

describe('wizard consumes the shared login-state lane', () => {
  it('imports the key-name mirror instead of typing literals into the payload', () => {
    expect(WIZARD).toMatch(/from '@\/utils\/loginProbeForm'/)
    expect(WIZARD).toMatch(/buildLoginProbeConfig/)
    expect(WIZARD).toMatch(/loginProbeGate/)
    // No hand-typed mode value anywhere in the wizard: the mirror owns it.
    expect(WIZARD).not.toMatch(/['"]state-read['"]/)
  })

  it('filters the ledger list through the shared consumable verdict', () => {
    expect(WIZARD).toMatch(/isConsumableSessionState/)
    expect(WIZARD).toMatch(/listSessionStates/)
    // Second-copy detectors for the row kind are the drift this rule forbids.
    expect(WIZARD).not.toMatch(/\brow\.kind\b|\bstates?\.kind\b|\bstate\.kind\b\s*[=!]==?/)
  })

  it('keeps the replay fields and the direct-read fields on separate display lanes', () => {
    expect(WIZARD).toMatch(/data-test="wizard-probe-mode"/)
    expect(WIZARD).toMatch(/data-test="wizard-state-name"/)
    expect(WIZARD).toMatch(/data-test="wizard-check-url"/)
  })
})

describe('wizard locale coverage for the direct-read lane', () => {
  const zhWizard = (zhCN as Record<string, any>).monitor.wizard
  const enWizard = (enUS as Record<string, any>).monitor.wizard

  it('carries every new key in both languages with non-empty text', () => {
    for (const key of NEW_WIZARD_KEYS) {
      expect(typeof zhWizard[key], `zh-CN monitor.wizard.${key}`).toBe('string')
      expect(zhWizard[key].trim()).not.toBe('')
      expect(typeof enWizard[key], `en-US monitor.wizard.${key}`).toBe('string')
      expect(enWizard[key].trim()).not.toBe('')
    }
  })

  it('points the direct-read hints at the settings page the user has to visit', () => {
    // Closed/empty states must name where to act, never just say something is missing.
    expect(zhWizard.stateNameHint).toContain('登录态账本')
    expect(enWizard.stateNameHint).toContain('login-state ledger')
    expect(zhWizard.needStateName).toContain('登录态账本')
  })
})

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Contract-upgrade difference step contract (MJ T717b-2): the stub wizard
 * reads the upgrade plan beside the draft preview, shows a three-section
 * difference step only when the space already has rules from a previous
 * contract version AND the plan names rows, offers the honest stale-mark
 * door (disable + flag, never delete), and never auto-overwrites rule
 * bodies — changed rows only point at the endpoint list page. Static source
 * assertions mirror the proven pattern in tests/ui/mockStubWizardShared.test.ts.
 */

const root = resolve(__dirname, '../../src')
const apiSrc = readFileSync(resolve(root, 'api/mock.ts'), 'utf8')
const wizardSrc = readFileSync(resolve(root, 'components/mock/MockStubDirectWizard.vue'), 'utf8')
const zhSrc = readFileSync(resolve(root, 'utils/locales/zh-CN.ts'), 'utf8')
const enSrc = readFileSync(resolve(root, 'utils/locales/en-US.ts'), 'utf8')

/** Keys of the difference step, required symmetrically in both locales. */
const DIFF_KEYS = [
  'diffStep', 'diffIntro', 'diffAdded', 'diffChanged', 'diffRemoved',
  'advCreate', 'advUpdateBody', 'advDisable', 'changedHint',
  'markRemovedBtn', 'markRemovedFail', 'markReceiptDone', 'markReceiptNone', 'diffContinue',
]

describe('contract-upgrade difference step', () => {
  it('api mirrors both admin doors with the exact urls and view shapes', () => {
    expect(apiSrc).toContain('export interface MockSpecUpgradeRowItem')
    expect(apiSrc).toContain('export interface MockStubDraftAdviceItem')
    expect(apiSrc).toContain('export interface MockSpecUpgradePlan')
    expect(apiSrc).toContain('export function fetchSpecUpgradePlan')
    expect(apiSrc).toContain('export function markRemovedUpgradePlan')
    expect(apiSrc).toContain('`/mock/spaces/${spaceId}/specs/${specId}/upgrade-plan`')
    expect(apiSrc).toContain('`/mock/spaces/${spaceId}/specs/${specId}/upgrade-plan/mark-removed`')
  })

  it('preview reads drafts and the plan together; the diff step only shows when meaningful', () => {
    expect(wizardSrc).toContain('fetchSpecUpgradePlan')
    expect(wizardSrc).toContain('Promise.all')
    expect(wizardSrc).toContain('diffShown')
    expect(wizardSrc).toContain('hasPrevious')
    expect(wizardSrc).toContain('data-test="stub-direct-diff-step"')
    expect(wizardSrc).toContain('data-test="stub-direct-diff-added"')
    expect(wizardSrc).toContain('data-test="stub-direct-diff-changed"')
    expect(wizardSrc).toContain('data-test="stub-direct-diff-removed"')
    expect(wizardSrc).toContain('data-test="stub-direct-mark-removed"')
    expect(wizardSrc).toContain('data-test="stub-direct-mark-removed-receipt"')
    expect(wizardSrc).toContain('data-test="stub-direct-diff-continue"')
  })

  it('removed rows reuse the endpoint-list stale badge (one spelling site-wide)', () => {
    expect(wizardSrc).toContain("t('mockEndpoints.staleTag')")
  })

  it('back from selection returns to the diff step when it was shown', () => {
    expect(wizardSrc).toContain('step = diffShown ? 1 : 0')
  })

  it('difference-step locale keys exist symmetrically for zh and en', () => {
    const zhBlock = zhSrc.slice(zhSrc.indexOf('stubImport: {'))
    const enBlock = enSrc.slice(enSrc.indexOf('stubImport: {'))
    for (const k of DIFF_KEYS) {
      expect(zhBlock, `zh key ${k}`).toContain(`${k}:`)
      expect(enBlock, `en key ${k}`).toContain(`${k}:`)
    }
  })
})

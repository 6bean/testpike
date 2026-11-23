/**
 * Layout/contract mirror for the set-internal (program_spec) subscription source.
 *
 * Posture = the ?raw source mirror used by apiTestArchT413a.test.ts: assertions read
 * the shipped template/script text of SpecSubscriptionPanel.vue plus both locale
 * tracks, so a regression that re-introduces a url-only form, drops the honest
 * empty-state branch, or desynchronises zh/en fails here before the browser renders it.
 *
 * Clauses :
 * 1. the form exposes a source-kind radio with both literal kinds (url | program_spec);
 * 2. program_spec renders provider project + provider spec selects, and an honest
 * empty-state hint when the project shares no active program (no dead dropdown);
 * 3. the list column keeps BOTH render branches hard-present (sub-source-provider /
 * sub-source-url), per the no-conditional-wrapped-assertion rule;
 * 4. only the program_spec branch carries the provider pair in the request body —
 * local validation covers form shape, semantic gates stay in the core service;
 * 5. every new key ships in zh-CN and en-US together (i18n dual-track rule);
 * 6. the source-kind caption never rebinds through the el-form-item label prop —
 * Element Plus auto-points label[for] at the radio-group container DIV (not a
 * labelable element) which trips the Chromium form-association audit; the group
 * carries an explicit aria-label instead (browser probe evidence, 2026-11-02).
 */
import { describe, expect, it } from 'vitest'

import panelRaw from '@/views/apitest/SpecSubscriptionPanel.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

const NEW_KEYS = [
  'colSource', 'sourceProvider', 'providerUnknown', 'fieldSourceKind',
  'sourceKindUrl', 'sourceKindProgram', 'programHint', 'fieldProviderProject',
  'providerProjectPh', 'fieldProviderSpec', 'providerSpecPh', 'providerNoSpecs',
  'providerEmpty', 'peerLoadFail', 'providerSpecLoadFail', 'guardHint',
]

describe('spec subscription program-source panel', () => {
  it('exposes the source-kind radio with both literal kinds', () => {
    expect(panelRaw).toContain('data-test="sub-source-kind-radio"')
    expect(panelRaw).toContain('<el-radio value="url">')
    expect(panelRaw).toContain('<el-radio value="program_spec">')
  })

  it('renders provider selects and the honest empty-state branch', () => {
    expect(panelRaw).toContain('data-test="sub-provider-project-select"')
    expect(panelRaw).toContain('data-test="sub-provider-spec-select"')
    expect(panelRaw).toContain('data-test="sub-provider-empty"')
    expect(panelRaw).toContain("t('apitest.subscription.providerEmpty')")
  })

  it('keeps both source render branches hard-present in the list column', () => {
    expect(panelRaw).toContain('data-test="sub-source-provider"')
    expect(panelRaw).toContain('data-test="sub-source-url"')
  })

  it('carries the provider pair only for program_spec and never duplicates core gates', () => {
    expect(panelRaw).toContain('providerProjectId: formProviderProjectId.value')
    expect(panelRaw).toContain('providerSpecName: formProviderSpecName.value.trim()')
    expect(panelRaw).toContain("formSourceKind.value === 'url'")
  })

  it('ships every new key in both locale tracks', () => {
    for (const k of NEW_KEYS) {
      expect(zhRaw.includes(`${k}:`), `zh-CN missing ${k}`).toBe(true)
      expect(enRaw.includes(`${k}:`), `en-US missing ${k}`).toBe(true)
    }
  })

  it('keeps the source-kind caption off the auto label-for association', () => {
    expect(panelRaw).not.toContain('<el-form-item :label="t(\'apitest.subscription.fieldSourceKind\')">')
    expect(panelRaw).toContain('class="sub-kind-caption"')
    expect(panelRaw).toContain(":aria-label=\"t('apitest.subscription.fieldSourceKind')\"")
  })
})

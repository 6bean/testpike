/**
 * requirement-list ID & fingerprint columns contract (frontend segment A).
 *
 * Pins, in order of risk:
 * - the api mirror carries the generated fingerprint field (admin
 * RequirementView is the source of truth; the type must not lag);
 * - id and fingerprint are configurable column rows (column settings keeps
 * its single list) and every present cell copies with a visible outcome
 * (no silent no-op; clipboard denial gets a manual-copy path);
 * - the exact-ID / fingerprint filter inputs are NOT here yet: they arrive
 * with their server parameters in the follow-up segment, and this file
 * negative-pins that a client-only fake filter never lands first;
 * - every new screen string exists in BOTH locales, equal shape, no
 * internal governance wording on screen (W6).
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const root = resolve(__dirname, '../..')
const viewSrc = readFileSync(resolve(root, 'src/views/requirement/RequirementListView.vue'), 'utf8')
const apiSrc = readFileSync(resolve(root, 'src/api/requirement.ts'), 'utf8')

const NEW_KEYS = [
  'requirement.colId',
  'requirement.colFingerprint',
  'requirement.copyValue',
  'requirement.copyValueDone',
  'requirement.copyValueFailed',
  'requirement.filterIdsPlaceholder',
  'requirement.filterFingerprintPlaceholder',
]

describe('T695 requirement id & fingerprint columns', () => {
  it('api mirror carries the generated fingerprint field', () => {
    expect(apiSrc).toMatch(/fingerprint\?: string \| null/)
  })

  it('id and fingerprint are configurable columns in the shared column list', () => {
    expect(viewSrc).toMatch(/\{ key: 'id', labelKey: 'requirement\.colId', width: 80 \}/)
    expect(viewSrc).toMatch(/\{ key: 'fingerprint', labelKey: 'requirement\.colFingerprint'/)
  })

  it('present cells copy with a visible outcome and an honest failure path', () => {
    expect(viewSrc).toMatch(/data-test="req-copy-value"/)
    expect(viewSrc).toMatch(/navigator\.clipboard\.writeText/)
    expect(viewSrc).toMatch(/copyValueDone/)
    expect(viewSrc).toMatch(/copyValueFailed/)
  })

  it('exact-id and fingerprint filters are wired to their server parameters', () => {
    // R25 follow-through from the previous version of this file: the inputs may
    // only exist together with the server-side filter they drive. Pin the whole
    // channel — request params, the two inputs, and the deep-link consumption.
    expect(viewSrc).toMatch(/ids: filters\.ids\.trim\(\)/)
    expect(viewSrc).toMatch(/fingerprint: filters\.fingerprint\.trim\(\)/)
    expect(viewSrc).toMatch(/data-test="req-filter-ids"/)
    expect(viewSrc).toMatch(/data-test="req-filter-fingerprint"/)
    // Deep-link consumption is the whole-slice URL rebuild (F1: applyUrlState
    // replaced applyDeepLinkFilters and must keep wiring both exact filters).
    expect(viewSrc).toMatch(/applyUrlState\(route\.query\)/)
    expect(viewSrc).toMatch(/filters\.ids = typeof q\.ids === 'string'/)
    expect(viewSrc).toMatch(/filters\.fingerprint = typeof q\.fingerprint === 'string'/)
    expect(apiSrc).toMatch(/fingerprint\?: string/)
    // The server owns id parsing (fail-closed on malformed tokens); the client
    // sends the raw comma-separated text instead of re-implementing it (R40).
    expect(apiSrc).toMatch(/typeof ids === 'string'/)
  })

  it('locale keys exist in both languages with identical shape and no governance wording', () => {
    for (const key of NEW_KEYS) {
      const zh = lookupLocale(zhCN, key)
      const en = lookupLocale(enUS, key)
      expect(zh, `zh missing ${key}`).toBeTruthy()
      expect(en, `en missing ${key}`).toBeTruthy()
      expect(String(zh)).not.toMatch(/人话|拒件|裁决|单源|棘轮|收口|出账/)
      expect(String(en)).not.toMatch(/plain[- ]language|single source|adjudicat/i)
    }
  })
})

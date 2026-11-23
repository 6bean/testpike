/**
 * Fidelity ledger echo wiring contract (source-as-text, EE batch .
 *
 * Pinned rules (preview echo of tp_doc_extract_fidelity on the extraction page):
 * - the api layer mirrors the admin ExtractionFidelityView fields
 * (injectedTokens / sourceTokens / retentionPercent / anchorRatePercent /
 * billedTokensPerItem) and the preview carries fidelity as a nullable echo;
 * - the dialog renders the info line ONLY when the server echoed a row — an
 * absent ledger hides the line entirely (no fabricated retention number);
 * - the displayed ratio is the server-booked retentionPercent verbatim: the
 * client must not recompute injected/source into a second ratio;
 * - reopening the dialog resets the echo (no stale account across documents);
 * - user-facing copy names what the numbers mean (read share, anchor fill,
 * per-item cost) in both locales.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/document.ts?raw'
import viewRaw from '@/views/ai/ExtractPreviewDialog.vue?raw'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const lookup = lookupLocale

describe('fidelity ledger echo wiring (T580 slice 2)', () => {
  it('api layer mirrors the fidelity view fields on a nullable echo', () => {
    expect(apiRaw).toMatch(
      /export interface ExtractionFidelity \{[\s\S]*?injectedTokens: number/,
    )
    expect(apiRaw).toMatch(
      /export interface ExtractionFidelity \{[\s\S]*?sourceTokens: number/,
    )
    expect(apiRaw).toMatch(
      /export interface ExtractionFidelity \{[\s\S]*?retentionPercent: number/,
    )
    expect(apiRaw).toMatch(
      /export interface ExtractionFidelity \{[\s\S]*?anchorRatePercent: number/,
    )
    expect(apiRaw).toMatch(
      /export interface ExtractionFidelity \{[\s\S]*?billedTokensPerItem: number/,
    )
    expect(apiRaw).toMatch(
      /export interface ExtractionPreview \{[\s\S]*?fidelity\?: ExtractionFidelity \| null/,
    )
  })

  it('info line renders only on an echoed row and shows the booked ratio verbatim', () => {
    expect(viewRaw).toContain('v-if="fidelity"')
    expect(viewRaw).toContain('data-test="extract-fidelity-row"')
    expect(viewRaw).toContain('data-test="extract-fidelity-retention"')
    expect(viewRaw).toContain('data-test="extract-fidelity-anchor"')
    // the tag text rides fidelity.retentionPercent directly — no client math
    expect(viewRaw).toMatch(/percent: fidelity\.retentionPercent,/)
    expect(viewRaw).not.toMatch(
      /retentionPercent\s*=\s*[\d.]*injectedTokens\s*\/\s*sourceTokens/,
    )
  })

  it('fetch assigns the echo and reopen resets it', () => {
    expect(viewRaw).toContain('fidelity.value = res.fidelity ?? null')
    expect(viewRaw).toContain('fidelity.value = null')
  })

  it('both locales carry the four fidelity strings', () => {
    for (const [key] of [
      ['document.extractFidelityRetention'],
      ['document.extractFidelityAnchor'],
      ['document.extractFidelityCost'],
      ['document.extractFidelityDetail'],
    ]) {
      expect(lookup(zhCN, key), `zh missing ${key}`).toBeTruthy()
      expect(lookup(enUS, key), `en missing ${key}`).toBeTruthy()
    }
  })
})

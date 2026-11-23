/**
 * Re-extract tri-state disposition wiring contract (source-as-text).
 *
 * Pinned rules (analysis item §6 / re-extract tri-state card):
 *  - the confirm door sends a per-item dispositions map keyed by preview item
 *    id; only the two reviewer-voiced choices travel (KEEP_AS_IS closes the
 *    item as skipped, FORCE_NEW lands a new row with an override audit);
 *  - an empty disposition keeps the server default path = update-proposal
 *    draft card awaiting one-click human confirmation (the manual confirmation
 *    door is never bypassed from the client);
 *  - the toast mirrors the admin ResultView three-key shape and names the
 *    drafted count so the reviewer knows proposals were issued;
 *  - disposition state is per selected row; rows without a selection echo
 *    the effective server default as a read-only tag (the reviewer sees the
 *    value that will apply, no phantom control, no auto-apply bypass).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/document.ts?raw'
import viewRaw from '@/views/ai/ExtractPreviewDialog.vue?raw'
import zhCN from '@/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '@/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

describe('re-extract disposition wiring', () => {
  it('api layer carries the dispositions map and the drafted result key', () => {
    expect(apiRaw).toMatch(
      /export interface ConfirmExtractionResult \{[^}]*drafted: number/,
    )
    expect(apiRaw).toContain("export type ExtractionDispositionName = 'KEEP_AS_IS' | 'FORCE_NEW'")
    expect(apiRaw).toMatch(
      /export function confirmExtraction\(\s*id: number,\s*items: ExtractionItem\[\],\s*dispositions\?: Record<number, ExtractionDispositionName>,?\s*\)/,
    )
    expect(apiRaw).toContain('data: { items, dispositions: dispositions ?? null }')
  })

  it('dialog offers per-row disposition and passes it through on confirm', () => {
    expect(viewRaw).toContain('data-test="extract-disposition-select"')
    expect(viewRaw).toContain("value=\"KEEP_AS_IS\"")
    expect(viewRaw).toContain("value=\"FORCE_NEW\"")
    // the reviewer-facing select stays gated by row selection (manual confirmation door untouched)
    expect(viewRaw).toMatch(/v-if="isRowSelected\(row\)"/)
    expect(viewRaw).toMatch(
      /confirmExtraction\(\s*props\.doc\.id,\s*selectedItems\.value,\s*\w+\(\)\s*\)/,
    )
    expect(viewRaw).toContain('skipped: res.skipped, drafted: res.drafted')
    // the manual confirmation door wording stays untouched (no zero-click auto-apply)
    expect(viewRaw).toContain('t(\'document.dispositionDefault\')')
  })

  it('unselected rows echo the effective default disposition as a read-only tag', () => {
    // the em-dash hid which value applies by default; the row must show it
    expect(viewRaw).toMatch(
      /v-else[^>]*data-test="extract-disposition-default"[^>]*>[\s\S]{0,120}t\('document\.dispositionDefault'\)/,
    )
    // echo reuses the single-source locale key already pinned by the select placeholder
    expect(viewRaw).toContain("t('document.dispositionDefault')")
    // no interactive control may leak onto unselected rows (phantom-control guard)
    expect(viewRaw).not.toMatch(/extract-disposition-na/)
  })

  it('locales name the three options and the toast counts drafted proposals', () => {
    for (const [dict, name] of [[zhCN, 'zh'], [enUS, 'en']] as const) {
      expect(lookup(dict, 'document.extractColDisposition'), `${name} column label`).toBeTruthy()
      expect(lookup(dict, 'document.dispositionDefault'), `${name} default option`).toBeTruthy()
      expect(lookup(dict, 'document.dispositionKeep'), `${name} keep option`).toBeTruthy()
      expect(lookup(dict, 'document.dispositionForceNew'), `${name} force-new option`).toBeTruthy()
      const toast = lookup(dict, 'document.confirmOk')
      expect(typeof toast === 'string' && toast.includes('{drafted}'), `${name} toast drafted`).toBe(true)
    }
  })
})

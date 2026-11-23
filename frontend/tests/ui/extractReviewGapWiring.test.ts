/**
 * Low-confidence review chips + coverage-gap banner wiring contract (source-as-text).
 *
 * Pinned rules (extraction preview review-flag echo + coverage-gap banner):
 *  - the preview api type mirrors the admin view three fields
 *    (reviewFlagThreshold / uncoveredChunkCount / uncoveredChunkIds);
 *  - the review chip verdict reads the SERVER-echoed threshold (hot-updatable
 *    config key), never a client-side hardcoded constant; a null echo falls
 *    back to the shipped default constant used only as the display floor;
 *  - the gap banner renders only when the ledger reports pending steps, the
 *    chunk ids are listed ascending and the visible list is capped so a
 *    hundred-step gap cannot blow the dialog layout;
 *  - reopening the dialog resets both surfaces (no stale echo across documents);
 *  - user-facing copy carries no internal governance vocabulary.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/document.ts?raw'
import viewRaw from '@/views/ai/ExtractPreviewDialog.vue?raw'
import zhCN from '@/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '@/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

describe('review-flag chip and gap banner wiring', () => {
  it('api layer mirrors the three preview contract fields', () => {
    expect(apiRaw).toMatch(
      /export interface ExtractionPreview \{[\s\S]*?reviewFlagThreshold: number \| null/,
    )
    expect(apiRaw).toMatch(
      /export interface ExtractionPreview \{[\s\S]*?uncoveredChunkCount: number/,
    )
    expect(apiRaw).toMatch(
      /export interface ExtractionPreview \{[\s\S]*?uncoveredChunkIds: number\[\]/,
    )
  })

  it('chip verdict reads the echoed threshold, not a per-row hardcoded constant', () => {
    expect(viewRaw).toContain('data-test="extract-review-chip"')
    // the comparison source is the echoed threshold ref (via the helper),
    // so the hot-updatable server value actually drives the render
    expect(viewRaw).toMatch(/function needsReview\(row: ExtractionItem\)/)
    expect(viewRaw).toMatch(/row\.confidence\s*<\s*reviewThreshold\.value/)
    // threshold ref is seeded from the payload with the shipped default fallback
    expect(viewRaw).toMatch(/reviewThreshold\.value\s*=\s*res\.reviewFlagThreshold\s*\?\?\s*DEFAULT/)
    // the confidence column is widened to host the chip without wrapping
    expect(viewRaw).toMatch(/extractColConfidence[^>]*width="120"/)
  })

  it('gap banner is ledger-driven, id-capped and never renders on a clean ledger', () => {
    expect(viewRaw).toContain('data-test="extract-gap-banner"')
    expect(viewRaw).toMatch(/v-else-if="state === 4"[\s\S]{0,500}uncoveredCount > 0[\s\S]{0,400}extract-gap-banner/)
    expect(viewRaw).toMatch(/const uncoveredIdsShown = computed/)
    // visible-id cap keeps a hundred-step gap from blowing the layout
    expect(viewRaw).toMatch(/uncoveredIds\.value\.slice\(0,\s*8\)/)
    expect(viewRaw).toMatch(/uncoveredIds\.length\s*>\s*8/)
  })

  it('reopen resets the echoed threshold and the gap ledger', () => {
    expect(viewRaw).toMatch(/function onOpen\(\)[\s\S]*?reviewThreshold\.value\s*=\s*DEFAULT_REVIEW_FLAG_THRESHOLD/)
    expect(viewRaw).toMatch(/function onOpen\(\)[\s\S]*?uncoveredCount\.value\s*=\s*0/)
    expect(viewRaw).toMatch(/function onOpen\(\)[\s\S]*?uncoveredIds\.value\s*=\s*\[\]/)
  })

  it('locales carry chip, tip and banner copy in both languages without governance words', () => {
    const governance = /人话|拒件|裁决|单源|棘轮|落章|出账|收口|plain-language|single source|adjudicat/i
    for (const [dict, name] of [[zhCN, 'zh'], [enUS, 'en']] as const) {
      const chip = lookup(dict, 'document.extractReviewChip')
      expect(typeof chip === 'string' && chip.length > 0, `${name} chip label`).toBe(true)
      const tip = lookup(dict, 'document.extractReviewTip')
      expect(typeof tip === 'string' && tip.includes('{percent}'), `${name} tip percent interpolation`).toBe(true)
      const banner = lookup(dict, 'document.extractGapBanner')
      expect(typeof banner === 'string' && banner.includes('{count}'), `${name} banner count interpolation`).toBe(true)
      for (const text of [chip, tip, banner]) {
        expect(governance.test(text as string), `${name} copy free of governance words`).toBe(false)
      }
    }
  })
})

/**
 * Requirement list view-original contract .
 *
 * The user asked for the same original-text interaction that the extraction
 * preview dialog offers, reachable from a requirement row. Two things must hold:
 * - the original text is rendered by ONE component (the extraction dialog and
 * the requirement list must not grow two renderers for the same answer),
 * - a point with no recorded provenance says so instead of opening a dead
 * shell: the action renders disabled and the tooltip names why and where to
 * fix it.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const at = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const viewer = at('../../src/components/SourceChunkViewerDialog.vue')
const extractDialog = at('../../src/views/ai/ExtractPreviewDialog.vue')
const reqView = at('../../src/views/requirement/RequirementListView.vue')
const reqApi = at('../../src/api/requirement.ts')

describe('requirement view-original single-source viewer', () => {
  it('the shared viewer reads the one server door and owns the verbatim rendering', () => {
    expect(viewer).toContain('getDocumentChunk')
    expect(viewer).toMatch(/data-test="extract-source-content"/)
    expect(viewer).toMatch(/data-test="extract-source-context"/)
    expect(viewer).toMatch(/:data-test="`extract-source-neighbor-\$\{nb\.id\}`"/)
  })

  it('both doors consume the shared component and neither keeps its own copy', () => {
    expect(extractDialog).toContain('SourceChunkViewerDialog')
    expect(reqView).toContain('SourceChunkViewerDialog')
    // A second inline renderer in either caller is the exact duplication defect.
    expect(extractDialog).not.toMatch(/data-test="extract-source-content"/)
    expect(extractDialog).not.toMatch(/data-test="extract-source-neighbor-/)
    expect(reqView).not.toMatch(/data-test="extract-source-content"/)
  })

  it('the requirement mirror carries the provenance anchor field for field', () => {
    expect(reqApi).toMatch(/export interface RequirementSourceAnchor \{/)
    for (const key of ['documentId', 'chunkIds', 'evidence']) {
      expect(reqApi, `anchor mirror missing ${key}`).toContain(`  ${key}?:`)
    }
    expect(reqApi).toMatch(/sourceAnchor\?: RequirementSourceAnchor \| null/)
  })

  it('a point without provenance renders a disabled door plus a readable reason', () => {
    expect(reqView).toMatch(/hasSourceAnchor/)
    expect(reqView).toMatch(/:disabled="!hasSourceAnchor\(row\)"/)
    expect(reqView).toMatch(/sourceDisabledTip\(row\)/)
    // The reason sentence names the empty field and the way out (three-element
    // error guidance), so it must not read like a bare "not available".
    const tip = lookupLocale(zhCN, 'requirement.viewSourceNoAnchor')
    expect(tip).toBeTruthy()
    expect(tip).toContain('来源')
    expect(lookupLocale(enUS, 'requirement.viewSourceNoAnchor')).toBeTruthy()
  })

  it('the action label and reason exist on both locale tracks', () => {
    for (const key of ['viewSource', 'viewSourceNoAnchor']) {
      expect(lookupLocale(zhCN, `requirement.${key}`), `zh missing ${key}`).toBeTruthy()
      expect(lookupLocale(enUS, `requirement.${key}`), `en missing ${key}`).toBeTruthy()
    }
  })

  it('an unreadable chunk still answers with the recorded verbatim evidence', () => {
    // The fallback face lives inside the shared viewer, not beside it: one
    // component answers both doors whether the chunk reads or not.
    expect(viewer).toContain('fallbackEvidence')
    expect(viewer).toContain('showFallback')
    expect(viewer).toMatch(/data-test="extract-source-fallback-note"/)
    expect(viewer).toMatch(/data-test="extract-source-fallback-evidence"/)
    // A failed read with evidence in hand keeps the dialog open; only a door
    // that has nothing to answer closes behind the server toast.
    expect(viewer).toMatch(
      /if \(hasFallback\.value\) \{\s*failed\.value = true\s*\} else \{\s*emit\('update:modelValue', false\)/,
    )
    // The requirement door opens for evidence-only anchors (the stored excerpt
    // is an answer) and hands that excerpt to the shared viewer.
    expect(reqView).toMatch(/function hasSourceAnchor[\s\S]*?evidence[\s\S]*?\n}/)
    expect(reqView).toMatch(/:fallback-evidence=/)
    // Same fallback travels on the extraction door: one defect, one answer.
    expect(extractDialog).toMatch(/:fallback-evidence=/)
  })

  it('the fallback wording names the cause and the way out on both tracks', () => {
    for (const key of ['extractSourceFallbackTitle', 'extractSourceFallbackNote']) {
      expect(lookupLocale(zhCN, `document.${key}`), `zh missing ${key}`).toBeTruthy()
      expect(lookupLocale(enUS, `document.${key}`), `en missing ${key}`).toBeTruthy()
    }
    const note = String(lookupLocale(zhCN, 'document.extractSourceFallbackNote'))
    // Three-element guidance: names what is gone, shows the excerpt, and
    // points at the literal entry that can rebuild a real anchor.
    expect(note).toContain('来源块')
    // The literal way-out must name the real sidebar leaf (R63: guidance that
    // points at a non-existent entry is a defect; renamed leaf landed 11-22).
    expect(note).toContain('需求提取')
  })
})

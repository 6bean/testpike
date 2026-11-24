/**
 * Conclusion-level traceability — the remaining two anchor families (round-2
 * item 3): extract-ledger evidence rows and the execution scene door.
 *
 * Both must consume the shared anchor doors from sourceAnchorJump (R40:
 * one reveal timing, one route builder, no per-domain hand-rolled
 * scrollIntoView or flash-timer copies — the old scrollToLog re-implemented
 * the exact 1600ms contract the shared door already owns):
 *  1. extract-ledger evidence anchor: the coverage-gap banner lists the
 *     pending chunk ids as clickable anchors that open the owning chunk
 *     verbatim through the existing single-source getDocumentChunk door
 *     (key-space fact: ledger ids are tp_document_chunk row ids, NOT
 *     knowledge vector chunk ids — the /knowledge door would silently drop
 *     them, so the honest target is the source viewer next door);
 *  2. execution anchor: the failure-reason block (the conclusion sentence)
 *     jumps in-page to the failed step inside the evidence panel via the
 *     panel's exposed reveal door, which consumes flashRevealElement;
 *  3. locale keys land on both zh/en tracks (dual-track i18n).
 */
import { describe, expect, it } from 'vitest'
import dialogRaw from '@/views/ai/ExtractPreviewDialog.vue?raw'
import viewerRaw from '@/components/SourceChunkViewerDialog.vue?raw'
import execViewRaw from '@/components/ExecTaskResultView.vue?raw'
import panelRaw from '@/components/ExecStepEvidencePanel.vue?raw'
import doorRaw from '@/composables/sourceAnchorJump.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('ledger evidence anchor (coverage-gap ids become jumpable)', () => {
  it('renders each pending chunk id as an anchor chip on the shared source door', () => {
    expect(dialogRaw).toContain('extract-gap-anchor-')
    expect(dialogRaw).toContain('openSourceById')
    // the read moved into the shared viewer component (one door, R40): the
    // dialog mounts it; the viewer calls the single-source getDocumentChunk.
    expect(dialogRaw).toContain('SourceChunkViewerDialog')
    expect(viewerRaw).toMatch(/getDocumentChunk\(props\.documentId, id\)/)
  })
  it('keeps the banner honest: chip hint + count text on both locale tracks', () => {
    expect(zhRaw).toContain('extractGapAnchorHint')
    expect(enRaw).toContain('extractGapAnchorHint')
  })
})

describe('execution anchor (conclusion sentence → failed-step scene, in page)', () => {
  it('the result view consumes the shared reveal door instead of hand-rolling scroll+timing', () => {
    expect(execViewRaw).toContain('flashRevealElement')
    expect(execViewRaw).not.toMatch(/scrollIntoView\(/)
    expect(execViewRaw).not.toMatch(/1600\b/)
  })
  it('the failure-reason block hosts the scene door', () => {
    expect(execViewRaw).toContain('exec-error-scene-link')
    expect(execViewRaw).toContain('revealErrorScene')
  })
  it('the evidence panel exposes one reveal door built on the shared helper', () => {
    expect(panelRaw).toContain('flashRevealElement')
    expect(panelRaw).toContain('revealStep')
    expect(panelRaw).toContain('triptych-step-')
    expect(panelRaw).toContain('defineExpose')
  })
  it('locale keys for the scene door land on both tracks', () => {
    for (const key of ['errorSceneView', 'errorSceneMissing']) {
      expect(zhRaw).toContain(key)
      expect(enRaw).toContain(key)
    }
  })
  it('single source holds: only the shared door files keep the scroll primitive', () => {
    expect(doorRaw).toContain('scrollIntoView')
    for (const raw of [dialogRaw, execViewRaw, panelRaw]) {
      expect(raw).not.toMatch(/scrollIntoView\(/)
    }
  })
})

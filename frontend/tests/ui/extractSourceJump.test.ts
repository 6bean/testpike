/**
 * AP-4 extraction-preview jump-back-to-source contract.
 *
 * The preview rows carry a source anchor (evidence + chunkId). The UI must:
 *  1. render a "View Source" action for anchored rows that fetches exactly one
 *     chunk through GET /ai/documents/{id}/chunks/{chunkId} (api mirror in
 *     document.ts — no client-side existence re-check; the server answers a
 *     stale/foreign chunk id as a 200 found=false answer without text);
 *  2. degrade honestly when chunkId is null: disabled button + a tooltip that
 *     names the reason (no silent no-op, per the control-scope rule);
 *  3. show the chunk verbatim with natural paragraphs preserved
 *     (white-space: pre-wrap — same root-cause family as the reasoning-stream
 *     fragment defect, so the render rule is pinned as a raw-file contract);
 *  4. keep zh/en locale keys in lockstep (dual-track i18n).
 *
 * Precedent for raw-file assertions: chatProcessItemWhitespacePreserve /
 * chatBadgeDiagnosis / chatDockResize.
 */
import { describe, expect, it } from 'vitest'
import dialogRaw from '@/views/ai/ExtractPreviewDialog.vue?raw'
import viewerRaw from '@/components/SourceChunkViewerDialog.vue?raw'
import apiRaw from '@/api/document.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

const NEW_KEYS = [
  'extractColSource',
  'extractSourceView',
  'extractSourceMissing',
  'extractSourceTitle',
  'extractSourceLoading',
  'extractSourceChapter',
  'extractSourceLoadFail',
  // + remnant ⑧: clickable chapter label + pending-anchor flag
  'extractChapterOpenTip',
  'extractAnchorPending',
  'extractAnchorPendingTip',
] as const

describe('extraction item api mirror carries the source anchor', () => {
  it('ExtractionItem exposes optional evidence and chunkId fields', () => {
    expect(apiRaw).toMatch(/evidence\?:\s*string\s*\|\s*null/)
    expect(apiRaw).toMatch(/chunkId\?:\s*number\s*\|\s*null/)
  })

  it('getDocumentChunk reads one chunk through the admin bridge endpoint', () => {
    expect(apiRaw).toMatch(/export function getDocumentChunk\(id: number, chunkId: number\): Promise<DocumentChunk>/)
    expect(apiRaw).toMatch(/url: `\/ai\/documents\/\$\{id\}\/chunks\/\$\{chunkId\}`, method: 'get'/)
  })
})

describe('preview dialog renders the jump action with honest degradation', () => {
  it('anchored rows render an enabled View Source button wired to openSource', () => {
    expect(dialogRaw).toMatch(/data-test="extract-source-btn"/)
    expect(dialogRaw).toMatch(/@click="openSource\(row\)"/)
  })

  it('unanchored rows render the disabled twin plus a reason tooltip (no silent no-op)', () => {
    expect(dialogRaw).toMatch(/data-test="extract-source-btn-disabled"/)
    expect(dialogRaw).toMatch(/v-if="row\.chunkId == null"/)
    expect(dialogRaw).toMatch(/:content="t\('document\.extractSourceMissing'\)"/)
    expect(dialogRaw).toMatch(/<el-button link type="primary" disabled/)
  })

  it('openSource only fires for a resolvable chunkId and the shared viewer surfaces the server error text', () => {
    expect(dialogRaw).toMatch(/if \(!props\.doc \|\| chunkId == null\) return/)
    expect(dialogRaw).toMatch(/sourceChunkId\.value = chunkId/)
    // The read and its failure sentence belong to the one viewer door now.
    expect(viewerRaw).toMatch(/getDocumentChunk\(props\.documentId, id\)/)
    expect(viewerRaw).toMatch(/bizErrorMsg\(e, t\('document\.extractSourceLoadFail'\)\)/)
  })
})

describe('source viewer keeps the chunk verbatim', () => {
  // The renderer moved into the shared component : the
  // extraction dialog and the requirement list both mount it, so the rules are
  // pinned on that single source plus the caller mounting it.
  it('the shared viewer is an append-to-body dialog with its own test anchors', () => {
    expect(viewerRaw).toMatch(/append-to-body/)
    expect(viewerRaw).toMatch(/data-test="extract-source-dialog"/)
    expect(viewerRaw).toMatch(/data-test="extract-source-content"/)
    expect(dialogRaw).toMatch(/<SourceChunkViewerDialog/)
  })

  it('content renders in a pre block with pre-wrap so natural paragraphs survive', () => {
    expect(viewerRaw).toMatch(/<pre[^>]*data-test="extract-source-content"/)
    expect(viewerRaw).toMatch(/\.source-content\s*\{[^}]*white-space:\s*pre-wrap/s)
  })
})

describe('chapter label opens the source through the single viewer door (T583 ⑧)', () => {
  it('anchored rows render the chapter tag wired to openSource, keyboard-equivalent', () => {
    expect(dialogRaw).toMatch(/data-test="extract-chapter-open"/)
    expect(dialogRaw).toMatch(/@click="openSource\(row\)"/)
    expect(dialogRaw).toMatch(/@keyup\.enter="openSource\(row\)"/)
  })

  it('the chapter tag reuses openSource — the fetch stays one call site in the shared viewer', () => {
    // No caller may fetch the chunk itself: the shared viewer is the only door,
    // so two entries can never drift apart.
    expect((dialogRaw.match(/await getDocumentChunk\(/g) || [])).toHaveLength(0)
    expect((viewerRaw.match(/await getDocumentChunk\(/g) || [])).toHaveLength(1)
  })
})

describe('unanchored rows are flagged before confirm, with the switch key named (T585 slice 3)', () => {
  it('pending-anchor tag renders only for rows without a chunkId and no stored id', () => {
    expect(dialogRaw).toMatch(/data-test="extract-anchor-pending"/)
    expect(dialogRaw).toMatch(/v-else-if="row\.chunkId == null"/)
  })

  it('both locales name the hot-updatable anchor switch key literally', () => {
    expect(zhRaw).toMatch(/extractAnchorPendingTip[^\n]*ai\.doc\.anchor-required\.enabled/)
    expect(enRaw).toMatch(/extractAnchorPendingTip[^\n]*ai\.doc\.anchor-required\.enabled/)
  })
})

describe('dual-track i18n covers every new key on both locales', () => {
  for (const key of NEW_KEYS) {
    it(`zh-CN and en-US both define ${key}`, () => {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    })
  }
})

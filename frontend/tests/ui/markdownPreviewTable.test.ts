// MarkdownPreview GFM pipe-table contract .
// vitest here is the node pure-logic env (no jsdom, repo convention), so this
// is the ?raw source-scan posture following the mockHeaderLayoutT396 and
// batchInsightCardContract precedent. Live behavior (real <table> grid in the
// dialog) is asserted by the DOM ledger of aichat_risk_card_vision_measure.py
// (tableCount + rawPipeLeak probes).
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const mdSrc = readFileSync(
  fileURLToPath(new URL('../../src/components/MarkdownPreview.vue', import.meta.url)), 'utf8')
const panelSrc = readFileSync(
  fileURLToPath(new URL('../../src/components/ChatPanel.vue', import.meta.url)), 'utf8')

describe('markdown answer rendering (T440 stage 4)', () => {
  it('lexer recognizes header+separator pipe tables', () => {
    expect(mdSrc).toMatch(/GFM pipe tables/)
    expect(mdSrc).toContain('<table class="md-table">')
    expect(mdSrc).toContain('<thead>')
    expect(mdSrc).toMatch(/lines\[i \+ 1\]\.includes\('-'\)/)
  })
  it('dock routes assistant answers through MarkdownPreview, users stay plain', () => {
    expect(panelSrc).toMatch(/v-if="m\.role === 'assistant'"[\s\S]{0,1200}?<MarkdownPreview[^>]*:source="m\.content"/)
    expect(panelSrc).toMatch(/v-else class="chat-msg-content">\{\{ m\.content \}\}<\/div>/)
    expect(panelSrc).toMatch(/import MarkdownPreview from '@\/components\/MarkdownPreview\.vue'/)
  })
  it('process toggle carries a state glyph (affordance direction visible)', () => {
    expect(panelSrc).toMatch(/chat-process-toggle-glyph[\s\S]{0,80}▸[\s\S]{0,40}▾/)
  })
  it('markdown answer surface drops pre-wrap (blocks own their line breaks)', () => {
    expect(panelSrc).toMatch(/\.chat-msg-content--md\s*\{[\s\S]{0,60}white-space:\s*normal/)
  })
  it('pipe table honors GFM column alignment markers', () => {
    // The separator row (`|---|---:|:--:|`) carries the column alignment the
    // answer author intended; dropping it left number columns ragged against
    // each other in the risk report grid (vision round 5 finding).
    expect(mdSrc).toMatch(/\[\\s:|-\\]/) // separator row still recognized
    expect(mdSrc).toMatch(/md-cell--right|md-cell--center/)
    expect(mdSrc).toMatch(/\.markdown-preview :deep\(\.md-table \.md-cell--right\)[\s\S]{0,80}text-align:\s*right/)
    expect(mdSrc).toMatch(/\.markdown-preview :deep\(\.md-table \.md-cell--center\)[\s\S]{0,80}text-align:\s*center/)
  })
  it('answer table scrolls inside its own area and keeps the header row (worst scale)', () => {
    // A 50+ row failure list used to inline its full height into the bubble:
    // the header scrolled away and the answer had no containment.
    expect(mdSrc).toContain('<div class="md-table-wrap">')
    expect(mdSrc).toMatch(/\.md-table-wrap\)\s*\{[\s\S]{0,160}overflow:\s*auto/)
    expect(mdSrc).toMatch(/\.md-table-wrap\)\s*\{[\s\S]{0,160}max-height:/)
    expect(mdSrc).toMatch(/table\.md-table thead th[\s\S]{0,120}position:\s*sticky/)
    expect(mdSrc).toMatch(/border-collapse:\s*separate/)
  })
})

/**
 * Stage-4 contract of the grounded-defect citation (AO batch, .
 *
 * The grounding kernel already emits defect-shaped citations (ai-service
 * GroundingKernel.KIND_DEFECT). The dialog must present and route them:
 * - every citation kind resolves through ONE label map, so a third kind can
 * never fall into the last branch of a binary ternary and be mislabeled
 * (a defect card reading "Knowledge doc" would be a silent wrong door);
 * - clicking a defect card opens the defect ledger pre-filtered by the cited
 * title through the page's own ?keyword contract — zero new page, zero new
 * query parameter, single-source deep link (G/W group: name the object and
 * make it reachable);
 * - the jump honours the menu guard: a member without the defect leaf gets an
 * honest refusal instead of a router error;
 * - both locales carry the new label (bilingual symmetry, no CJK literals in
 * the component source).
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import panelRaw from '@/components/ChatPanel.vue?raw'
import jumpRaw from '@/composables/sourceAnchorJump.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import defectViewRaw from '@/views/defect/DefectListView.vue?raw'

describe('citation kind label is a single map, not a binary ternary', () => {
  it('resolves help / knowledge / defect through one keyed lookup', () => {
    expect(panelRaw).toMatch(/citeKindLabelKey|CITE_KIND_LABEL/)
    expect(panelRaw).toMatch(/defect:\s*'layout\.chatDock\.citeKindDefect'/)
    // The old binary ternary (knowledge as the unconditional else) is gone.
    expect(panelRaw).not.toMatch(/c\.kind === 'help'\s*\?\s*t\([^)]*\)\s*:\s*t\([^)]*\)\s*\}\}/)
  })

  it('unknown kinds degrade to a generic label instead of a wrong door', () => {
    expect(panelRaw).toMatch(/citeKindOther|fallback/)
  })
})

describe('defect citation card routes to the defect ledger', () => {
  it('pushes the existing ledger path with the cited title as keyword', () => {
    expect(panelRaw).toMatch(/path:\s*'\/defects'/)
    expect(panelRaw).toMatch(/keyword:\s*c\.title/)
  })

  it('consumes the ledger page\'s own keyword contract (no new parameter)', () => {
    expect(defectViewRaw).toMatch(/route\.query\.keyword/)
  })

  it('keeps help and knowledge doors untouched', () => {
    expect(panelRaw).toMatch(/openHelp\(/)
    // Since the grounding deep-link refactor (knowledgeCitationRoute, R40
    // single-sourced in composables/sourceAnchorJump) the panel routes the
    // knowledge door through the helper; the door target itself is unchanged
    // and now pinned where the literal actually lives.
    expect(panelRaw).toMatch(/knowledgeCitationRoute\(/)
    expect(jumpRaw).toMatch(/path:\s*'\/knowledge'/)
  })
})

describe('the defect jump keeps role filtering single-sourced in the server', () => {
  // The read path is exempt from menu perms by standing ruling (routes.ts:
  // read-only leaves must not be blocked from read-only members) and the
  // grounding kernel already trims manager-only rows per role. The dialog must
  // NOT copy a second role/permission judgment — it only renders what arrived.
  it('carries no client-side role check for citations', () => {
    const citeSection = panelRaw.slice(panelRaw.indexOf('function openCitation'), panelRaw.indexOf('function openCitation') + 1200)
    expect(citeSection).not.toMatch(/hasPerm|isAdmin|roles\.|perms\./)
  })

  it('routes only kinds it actually received; anything else opens no door', () => {
    const citeSection = panelRaw.slice(panelRaw.indexOf('function openCitation'), panelRaw.indexOf('function openCitation') + 1200)
    expect(citeSection).toMatch(/openHelp\(/)
    expect(citeSection).toMatch(/path: '\/defects'/)
    // The knowledge door is guarded by its own kind check: an unknown kind
    // (a future fourth source family) must not fall into it and get a fake
    // document link. Post-refactor shape: the branch calls the single-source
    // route helper; the /knowledge literal is owned by sourceAnchorJump.
    expect(citeSection).toMatch(/if\s*\(c\.kind === 'knowledge'\)[\s\S]{0,120}knowledgeCitationRoute\(/)
    expect(jumpRaw).toMatch(/path:\s*'\/knowledge'/)
  })

  it('never talks to ai-service or the defect table directly for the jump', () => {
    expect(panelRaw).not.toMatch(/18084|8084|localhost:8081/)
  })
})

describe('bilingual locales carry the new citation labels', () => {
  it('both locales define every citeKind key the component references', () => {
    const keys = [...panelRaw.matchAll(/layout\.chatDock\.(citeKind\w+)/g)].map((m) => m[1])
    expect(new Set(keys).size).toBeGreaterThan(2)
    for (const key of keys) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
  })

  it('the defect label names the record, not the mechanism', () => {
    expect(zhRaw).toMatch(/citeKindDefect:\s*'缺陷记录'/)
    expect(enRaw).toMatch(/citeKindDefect:\s*'Defect record'/)
  })

  it('zh and en citeKind key sets are symmetric', () => {
    const zhKeys = [...zhRaw.matchAll(/\b(citeKind\w+):/g)].map((m) => m[1]).sort()
    const enKeys = [...enRaw.matchAll(/\b(citeKind\w+):/g)].map((m) => m[1]).sort()
    expect(zhKeys).toEqual(enKeys)
  })
})

describe('no hard-coded wording in the component', () => {
  it('the cite card template carries zero CJK literals', () => {
    const template = panelRaw.slice(0, panelRaw.indexOf('<script'))
    const citeBlock = template.slice(template.indexOf('chat-cite-card'), template.indexOf('</button>', template.indexOf('chat-cite-card')))
    // eslint-disable-next-line no-control-regex
    expect(citeBlock).not.toMatch(/[\u4e00-\u9fff]/)
  })

  it('reads the shipped source files (guard against stale raw imports)', () => {
    expect(readFileSync('src/components/ChatPanel.vue', 'utf8')).toContain('chat-cite-card')
  })
})

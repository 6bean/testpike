import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Draft-list worst-scale contract (MJ P7): a 500-endpoint preview must not
 * inline-render 500 cards. The agreed shape is windowed rendering (mounted
 * DOM units ≤ 2x the visible unit count, honest spacers above/below),
 * grouping by the first resource-path segment with collapse, and the bulk
 * selection family: check-all-of-filter (existing) / AI-advice-only /
 * invert, plus an expandable unchecked list for review.
 *
 * Posture mirrors tests/ui/mockPreviewFirstFrameNotice.test.ts: pure
 * function unit tests for the window helper plus block-level source
 * assertions for the wizard wiring (no jsdom geometry in this harness).
 */

const root = resolve(__dirname, '../../src')
const repoRoot = resolve(__dirname, '../../..')
const windowSrc = readFileSync(resolve(root, 'composables/mockDraftWindow.ts'), 'utf8')
const wizardSrc = readFileSync(resolve(root, 'components/mock/MockStubDirectWizard.vue'), 'utf8')
const apiSrc = readFileSync(resolve(root, 'api/mock.ts'), 'utf8')
const controllerSrc = readFileSync(
  resolve(repoRoot, 'admin/src/main/java/com/sixbean/testpike/admin/mock/api/controller/MockStubImportController.java'),
  'utf8',
)
const zhSrc = readFileSync(resolve(root, 'utils/locales/zh-CN.ts'), 'utf8')
const enSrc = readFileSync(resolve(root, 'utils/locales/en-US.ts'), 'utf8')

function blockOf(src: string, marker: string): string {
  const at = src.indexOf(marker)
  if (at < 0) return ''
  const start = src.indexOf('{', at)
  let depth = 0
  for (let j = start; j < src.length; j++) {
    const ch = src[j]
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return src.slice(start + 1, j)
    }
  }
  return ''
}

/** Extract `key: 'value'` pairs from a locale object block. */
function localeKeys(block: string): Set<string> {
  const out = new Set<string>()
  for (const m of block.matchAll(/^\s*([A-Za-z0-9_]+):\s/gm)) out.add(m[1])
  return out
}

const zhStubImport = blockOf(zhSrc, 'stubImport: {')
const enStubImport = blockOf(enSrc, 'stubImport: {')

describe('draft window helper (pure)', () => {
  it('declares unit height and visible unit count as single-source constants', () => {
    expect(windowSrc).toMatch(/export const DRAFT_UNIT_HEIGHT_PX\s*=\s*\d+/)
    expect(windowSrc).toMatch(/export const DRAFT_VISIBLE_UNITS\s*=\s*\d+/)
  })

  it('never mounts more than 2x the visible unit count across a full scroll of 500 rows', async () => {
    const mod = await import('../../src/composables/mockDraftWindow')
    const visible = mod.DRAFT_VISIBLE_UNITS
    const unitH = mod.DRAFT_UNIT_HEIGHT_PX
    const total = 500
    for (let scroll = 0; scroll <= total * unitH; scroll += unitH) {
      const w = mod.draftWindowRange(scroll, total)
      expect(w.end - w.start, `window span at scroll ${scroll}`).toBeLessThanOrEqual(2 * visible)
      expect(w.start).toBeGreaterThanOrEqual(0)
      expect(w.end).toBeLessThanOrEqual(total)
      expect(w.padTopPx).toBe(w.start * unitH)
      expect(w.padBottomPx).toBe((total - w.end) * unitH)
    }
  })

  it('a list no larger than the mount budget renders fully with no spacers', async () => {
    const mod = await import('../../src/composables/mockDraftWindow')
    const w = mod.draftWindowRange(0, 5)
    expect(w.start).toBe(0)
    expect(w.end).toBe(5)
    expect(w.padTopPx).toBe(0)
    expect(w.padBottomPx).toBe(0)
  })

  it('groups by the first resource-path segment with an honest root bucket', async () => {
    const mod = await import('../../src/composables/mockDraftWindow')
    expect(mod.pathGroupKey('/orders/{id}/items')).toBe('orders')
    expect(mod.pathGroupKey('orders')).toBe('orders')
    expect(mod.pathGroupKey('/health')).toBe('health')
    expect(mod.pathGroupKey('/')).toBe('')
    expect(mod.pathGroupKey('')).toBe('')
  })

  it('flattens to group-header + row units and drops rows of collapsed groups only', async () => {
    const mod = await import('../../src/composables/mockDraftWindow')
    const rows = [
      { pathPattern: '/orders/a' },
      { pathPattern: '/users/b' },
      { pathPattern: '/orders/c' },
    ]
    const flat = mod.flattenDraftUnits(rows, new Set<string>())
    const heads = flat.filter((u) => u.kind === 'group')
    expect(heads.length).toBe(2)
    expect(flat.length).toBe(5)
    const collapsed = mod.flattenDraftUnits(rows, new Set<string>(['orders']))
    expect(collapsed.filter((u) => u.kind === 'draft').length).toBe(1)
    expect(collapsed.filter((u) => u.kind === 'group').length).toBe(2)
  })
})

describe('wizard wiring (static contract)', () => {
  it('previews through the advice-joined door so the AI flag rides core verdicts', () => {
    expect(apiSrc).toContain('stub-drafts-advised')
    expect(controllerSrc).toContain('stub-drafts-advised')
    expect(wizardSrc).toContain('previewStubDraftsAdvised')
  })

  it('renders the windowed slice, never the whole filtered list', () => {
    expect(wizardSrc).not.toContain('v-for="d in filteredDrafts"')
    expect(wizardSrc).toContain('windowedUnits')
    expect(wizardSrc).toMatch(/@scroll/)
    expect(wizardSrc).toContain('padTopPx')
    expect(wizardSrc).toContain('padBottomPx')
  })

  it('group headers toggle collapse', () => {
    expect(wizardSrc).toContain('stub-direct-group-toggle')
    expect(wizardSrc).toContain('collapsedGroups')
    expect(wizardSrc).toContain('flattenDraftUnits')
  })

  it('bulk family: check-all-of-filter stays, advice-only and invert join', () => {
    expect(wizardSrc).toContain('stub-direct-check-all')
    const onlyAi = blockOf(wizardSrc, 'function selectOnlyAi')
    expect(onlyAi, 'selectOnlyAi must exist').not.toBe('')
    // The AI flag is consumed from core's advice, never re-decided here.
    expect(onlyAi).toContain('defaultChecked')
    const invert = blockOf(wizardSrc, 'function invertSelection')
    expect(invert, 'invertSelection must exist').not.toBe('')
    expect(invert).toContain('filteredDrafts')
    expect(wizardSrc).toContain('stub-direct-only-ai')
    expect(wizardSrc).toContain('stub-direct-invert')
  })

  it('unchecked list is expandable for review with an honest cap', () => {
    expect(wizardSrc).toContain('stub-direct-unchecked-toggle')
    const block = blockOf(wizardSrc, 'uncheckedKeys')
    expect(block, 'uncheckedKeys must exist').not.toBe('')
    expect(wizardSrc).toContain('uncheckedShown')
  })

  it('new locale keys exist bilingually with identical additions', () => {
    const added = ['onlyAi', 'invert', 'uncheckedToggle', 'uncheckedEmpty', 'groupCount']
    const zhKeys = localeKeys(zhStubImport)
    const enKeys = localeKeys(enStubImport)
    for (const k of added) {
      expect(zhKeys.has(k), `zh stubImport missing ${k}`).toBe(true)
      expect(enKeys.has(k), `en stubImport missing ${k}`).toBe(true)
    }
  })
})

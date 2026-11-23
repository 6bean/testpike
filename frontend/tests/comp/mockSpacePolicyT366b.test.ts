/**
 * miss-policy wiring contract for the mock spaces workbench
 * (?raw source-scan posture, mockHeaderLayoutT396 precedent).
 *
 * What must never regress:
 * 1. the API mirror carries the ladder: MockSpaceItem.missPolicy, create
 * payload missPolicy, and the updateSpacePolicy PUT door — without them the
 * backend policy bridge (admin PUT /mock/spaces/{id}/policy) is unreachable;
 * 2. the table column is inline-editable ONLY for perm:mock:write operators and
 * falls back to a static honest label otherwise (write key decided once,
 * no second spectrum);
 * 3. the inline save is one-way bound (@change -> savePolicy, never v-model
 * two-way on the row) so a refused write can never leave the UI lying;
 * '40901' routes to the reload-then-retry path like the transition door;
 * 4. the create dialog ships the three-rung select with a per-rung plain-word
 * hint (passthrough needs a bound env; llm-fallback is double-gated);
 * 5. i18n dual track: every key the page uses exists in both zh-CN and en-US.
 */
import { describe, expect, it } from 'vitest'

import apiRaw from '@/api/mock.ts?raw'
import spacesRaw from '@/views/mock/MockSpacesView.vue?raw'
import themeRaw from '@/styles/theme.scss?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T366 blade 6b: api/mock.ts mirrors the space miss-policy contract', () => {
  it('MockSpaceItem carries missPolicy (admin MockSpaceView mirror)', () => {
    expect(apiRaw).toMatch(/missPolicy: string \| null/)
  })

  it('create payload accepts the rung (null = server default)', () => {
    expect(apiRaw).toMatch(/missPolicy\?: string \| null/)
  })

  it('updateSpacePolicy PUTs /mock/spaces/{id}/policy with the optimistic lock', () => {
    expect(apiRaw).toContain('export function updateSpacePolicy')
    expect(apiRaw).toMatch(/url: `\/mock\/spaces\/\$\{spaceId\}\/policy`/)
    expect(apiRaw).toMatch(/method: 'put'/)
    expect(apiRaw).toMatch(/data: \{ missPolicy, expectedVersion \}/)
  })
})

describe('T366 blade 6b: MockSpacesView wires the ladder into table + create dialog', () => {
  it('policy column renders the rung select only for write-permitted operators', () => {
    expect(spacesRaw).toMatch(/v-if="canWrite"\s+[\s\S]{0,220}mock-spaces-policy-select/)
    expect(spacesRaw).toContain('mock-spaces-policy-static')
  })

  it('inline save is one-way bound (model-value + change, never v-model on the row)', () => {
    expect(spacesRaw).toMatch(/:model-value="row\.missPolicy \?\? 'llm-fallback'"/)
    expect(spacesRaw).toMatch(/@change="\(value: string\) => savePolicy\(row, value\)"/)
  })

  it('savePolicy routes 40901 to the honest reload path (shared with transition door)', () => {
    const fn = /async function savePolicy[\s\S]*?\n}/.exec(spacesRaw)?.[0] ?? ''
    expect(fn).toContain('updateSpacePolicy')
    expect(fn).toContain("e.code === '40901'")
    expect(fn).toContain('await loadSpaces()')
  })

  it('the three-rung spectrum mirrors the shared core list, in order', () => {
    expect(spacesRaw).toMatch(/const missPolicyRungs = \['passthrough', 'reject', 'llm-fallback'\]/)
  })

  it('create dialog ships the rung select and the per-rung hint under the model', () => {
    expect(spacesRaw).toContain('mock-spaces-f-misspolicy')
    expect(spacesRaw).toMatch(/t\(`mockSpaces\.missPolicyHint\.\$\{form\.missPolicy\}`\)/)
  })

  it('create submits the chosen rung; birth default is llm-fallback (server default parity)', () => {
    expect(spacesRaw).toMatch(/missPolicy: form\.value\.missPolicy,/)
    expect(spacesRaw).toMatch(/missPolicy: 'llm-fallback' as string/)
  })
})

describe('T366 blade 6b: i18n dual track for every key the face uses', () => {
  const keys = [
    'colMissPolicy',
    'fieldMissPolicy',
    'policySaved',
    'policySaveFailed',
    'missPolicy:',
    'missPolicyHint:',
    'searchPh',
  ]
  it.each(keys)('zh-CN and en-US both define %s', (key) => {
    expect(zhRaw).toContain(key)
    expect(enRaw).toContain(key)
  })

  it('both locales name all three rungs and all three hints', () => {
    for (const src of [zhRaw, enRaw]) {
      const block = /missPolicy: \{[\s\S]*?\n    \}/.exec(src)?.[0] ?? ''
      expect(block).toContain('passthrough')
      expect(block).toContain('reject')
      expect(block).toContain('llm-fallback')
      const hints = /missPolicyHint: \{[\s\S]*?\n    \}/.exec(src)?.[0] ?? ''
      expect(hints).toContain('passthrough')
      expect(hints).toContain('reject')
      expect(hints).toContain('llm-fallback')
    }
  })
})

/**
 * Vision-gate remediations close): the four-dimension scoring pass
 * measured two hard violations on a 32-row worst-scale fixture, both now
 * programmatic so they cannot silently come back.
 * - dim4=2 (scale safety): the workbench inlined every space with no pager
 * and no search → an instant filter plus a client-side 20-row window.
 * - ratio iron law: the create dialog measured 520x864 (≈0.6:1, a narrow
 * stripe) → widened to 800px with short fields paired two per row.
 */
describe('T366 blade 6b: vision-gate layout contract (worst scale + dialog ratio)', () => {
  it('filter-bar carries an instant search over name/spaceKey (no page-level project selector)', () => {
    expect(spacesRaw).toContain('mock-spaces-filter-bar')
    expect(spacesRaw).toContain('mock-spaces-search')
    expect(spacesRaw).toMatch(/v-model="keyword"/)
    expect(spacesRaw).toMatch(/s\.name\.toLowerCase\(\)\.includes\(kw\)\s*\|\|\s*s\.spaceKey\.toLowerCase\(\)\.includes\(kw\)/)
    // iron law 1: no second project selector — header context stays the single source
    expect(spacesRaw).not.toMatch(/mock-spaces-filter-bar[\s\S]{0,400}<ProjectSelect/)
  })
  it('the table pages at 20 rows over the filtered ledger with a pager bar', () => {
    expect(spacesRaw).toMatch(/const pageSize = 20/)
    expect(spacesRaw).toMatch(/:data="pagedSpaces"/)
    expect(spacesRaw).toContain('mock-spaces-pager')
    expect(spacesRaw).toMatch(/filtered\.value\.slice\(\(page\.value - 1\) \* pageSize/)
  })
  it('the create dialog is widened and pairs short fields two per row', () => {
    expect(spacesRaw).toMatch(/width="900px"/)
    expect(spacesRaw).toMatch(/class="dialog-form-pair"/)
    // pairing is driven by the one global utility, never a scoped copy
    expect(themeRaw).toMatch(/\.dialog-form-pair\s*\{[^}]*grid-template-columns:\s*1fr 1fr/)
  })
  it('refresh sits in the filter-bar trailing slot (R23 stage-2: shell retired; the write action rides the filter-bar leading slot)', () => {
    // the title shell and its header-actions row are gone; the create entry
    // is the FIRST interactive element of the filter-bar row, refresh stays
    // in the trailing slot after the screening controls.
    expect(spacesRaw).not.toMatch(/<div class="header-actions">/)
    const bar = /data-test="mock-spaces-filter-bar">([\s\S]*?)<\/div>\s*<div v-if="!projectId"/.exec(spacesRaw)?.[0] ?? ''
    expect(bar).toContain('mock-spaces-new')
    expect(spacesRaw).toContain('filter-bar-actions')
    expect(spacesRaw).toMatch(/filter-bar-actions[\s\S]*?mock-spaces-refresh/)
    expect(spacesRaw.slice(0, spacesRaw.indexOf('mock-spaces-refresh'))).toContain('mock-spaces-new')
  })
})

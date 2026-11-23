/**
 * a layout contract: request drag-reorder wiring in the API
 * test rail (reorder entry -> full-order PUT mirror -> shared drag kernel).
 *
 * Posture = the ?raw source mirror used by apiTestArchT413a.test.ts, so a
 * regression that re-introduces a second drag engine or forgets the entry
 * gate fails here before the browser renders it.
 *
 * Clauses (design basis = door + the prescan card):
 * 1. api mirror reorderRequests exists and PUTs the FULL ordered id list
 * to the door /apitest/requests/{collectionId}/order;
 * 2. the rail head carries a reorder entry (data-test=apitest-reorder-btn)
 * gated OFF while no collection / no requests / an active filter — the
 * core door validates the full order honestly and a partial submit
 * would 400, so the entry gate is the prevention; the tooltip names the
 * blocking reason in plain language;
 * 3. reorder mode and the windowed row list are mutually exclusive (one
 * v-if chain) — no second row-list state mounted side by side;
 * 4. the panel reuses useStepDragSort + StepDragList (red line 6: one drag
 * kernel) and submits request ids in the current buffer order;
 * 5. every user-visible string lives in the apitest.reorder i18n key
 * family in BOTH locales (dual-track iron rule, zero hard-coded CJK in
 * the new component).
 */
import { describe, expect, it } from 'vitest'

import viewRaw from '@/views/apitest/ApiTestView.vue?raw'
import panelRaw from '@/views/apitest/RequestReorderPanel.vue?raw'
import apiRaw from '@/api/apiTest.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T414 slice 3-a: api mirror to the slice-1 full-order door', () => {
  it('exports reorderRequests PUTting the ordered id list', () => {
    expect(apiRaw).toMatch(/export function reorderRequests\(/)
    expect(apiRaw).toContain('`/apitest/requests/${collectionId}/order`')
    expect(apiRaw).toMatch(/reorderRequests[\s\S]{0,200}method:\s*'put'/)
  })

  it('the view consumes the mirror through the api layer, not a raw fetch', () => {
    expect(viewRaw).toMatch(/import \{[^}]*\breorderRequests\b[^}]*\} from '@\/api\/apiTest'/)
    expect(viewRaw).toContain('reorderRequests(collectionId.value, orderedRequestIds)')
  })
})

describe('T414 slice 3-a: entry gate = the partial-order prevention', () => {
  it('rail head carries the reorder entry bound to a computed disabled gate', () => {
    expect(viewRaw).toContain('data-test="apitest-reorder-btn"')
    expect(viewRaw).toContain(':disabled="reorderDisabled"')
    expect(viewRaw).toMatch(/const reorderDisabled = computed\(/)
  })

  it('the blocked-reason verdict names filter / no-collection / empty-list', () => {
    const blocked = viewRaw.match(/const reorderBlockedReason[\s\S]{0,500}/)
    expect(blocked).not.toBeNull()
    expect(blocked![0]).toContain('collectionId.value === null')
    expect(blocked![0]).toMatch(/requests\.value\.length === 0/)
    expect(blocked![0]).toMatch(/requestKeyword\.value\.trim\(\) !== ''/)
  })

  it('tooltip surfaces the plain-language reason when the entry is disabled', () => {
    expect(viewRaw).toMatch(/reorderBlockedReason \|\| t\('apitest\.reorder\.tip'\)/)
    expect(viewRaw).toContain("t('apitest.reorder.entry')")
  })
})

describe('T414 slice 3-a: reorder mode swaps with the windowed list', () => {
  it('mounts RequestReorderPanel only while reorder mode is open', () => {
    expect(viewRaw).toMatch(/import RequestReorderPanel from '@\/views\/apitest\/RequestReorderPanel\.vue'/)
    expect(viewRaw).toContain('<RequestReorderPanel')
    expect(viewRaw).toContain('v-else-if="reorderOpen"')
  })

  it('the panel sits before the windowed ul in one v-if chain (no double row state)', () => {
    const panelIdx = viewRaw.indexOf('<RequestReorderPanel')
    const ulIdx = viewRaw.indexOf('data-test="apitest-request-rows"')
    expect(panelIdx).toBeGreaterThan(-1)
    expect(ulIdx).toBeGreaterThan(-1)
    expect(panelIdx).toBeLessThan(ulIdx)
    // the windowed list stays the v-else tail of the same chain
    expect(viewRaw).toMatch(/v-else\s+ref="requestListEl"/)
  })

  it('closing reorder re-syncs the window to the first match', () => {
    expect(viewRaw).toMatch(/watch\(\s*reorderOpen[\s\S]{0,300}reqWindow\.resetWindow\(\)[\s\S]{0,120}reqWindow\.sync\(\)/)
  })
})

describe('T414 slice 3-a: panel reuses the shared drag kernel', () => {
  it('consumes useStepDragSort + StepDragList, no new drag engine', () => {
    expect(panelRaw).toContain("import { useStepDragSort")
    expect(panelRaw).toContain("import StepDragList from '@/components/StepDragList.vue'")
    expect(panelRaw).not.toMatch(/sortablejs|vuedraggable|v-draggable/)
  })

  it('maps rows to {method} {name} descriptions carrying the request id', () => {
    expect(panelRaw).toMatch(/description: `\$\{r\.method\} \$\{r\.name\}`/)
    expect(panelRaw).toMatch(/id: r\.id/)
  })

  it('server-side reference change resets the buffer (no stale local order)', () => {
    expect(panelRaw).toMatch(/watch\(\s*\(\) => props\.requests[\s\S]{0,300}core\.reset\(/)
  })

  it('save emits the ordered id list; save disabled until dirty', () => {
    expect(panelRaw).toMatch(/\(core\.ordered\.value as unknown as RequestDragItem\[\]\)\.map\(\(item\) => item\.id\)/)
    expect(panelRaw).toContain("emit('save', ids)")
    expect(panelRaw).toContain(':disabled="!core.dirty.value"')
    expect(panelRaw).toContain('data-test="apitest-reorder-save"')
    expect(panelRaw).toContain('data-test="apitest-reorder-undo"')
    expect(panelRaw).toContain('data-test="apitest-reorder-close"')
  })

  it('new component carries zero hard-coded CJK (open-source English-comment rule + i18n dual track)', () => {
    expect(panelRaw).not.toMatch(/[\u4e00-\u9fff]/)
  })
})

describe('T414 slice 3-a: i18n key family in both locales', () => {
  const keys = ['entry', 'tip', 'hint', 'undo', 'save', 'saved', 'close', 'empty', 'filterTip', 'noCollectionTip', 'emptyListTip', 'noChangeTip']

  it.each([['zh', zhRaw], ['en', enRaw]])('%s locale defines apitest.reorder.* completely', (_name, raw) => {
    const block = raw.match(/reorder: \{[\s\S]*?\n    \}/)
    expect(block).not.toBeNull()
    keys.forEach((k) => expect(block![0]).toContain(`${k}:`))
  })

  it('view/panel reference only t()-wrapped reorder strings', () => {
    for (const k of ['entry', 'hint', 'undo', 'save', 'saved', 'close', 'empty', 'filterTip', 'noChangeTip', 'panelAria']) {
      expect(`${viewRaw}${panelRaw}`).toContain(`t('apitest.reorder.${k}')`)
    }
  })
})

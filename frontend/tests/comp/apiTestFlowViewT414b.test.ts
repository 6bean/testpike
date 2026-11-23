/**
 * b layout contract: the rail "flow" view (what you draw is
 * what runs, honest single-source face).
 *
 * Posture = the ?raw source mirror used by apiTestArchT413a.test.ts: the
 * assertions read the shipped template text of ApiTestView.vue, so a
 * regression that re-introduces a second execution-order truth (a locally
 * re-sorted list, a hand-drawn diagram fed by anything but
 * previewRunSet) fails here before the browser renders it.
 *
 * Clauses (recon ruling docs/task-archives/PROGRESS_card_t414segment3_prescan.md):
 * 1. the request section carries a browse/flow two-state toggle
 * (data-test=apitest-flow-toggle) — no el-tabs elsewhere, no second page;
 * 2. flow mode replaces the windowed browse rows (mutual exclusion —
 * both states never mount the row source twice, red line 6);
 * 3. flow rows come from previewRunSet (the launch/execution same-source
 * contract) and are windowed with the shared useWindowedList — the
 * worst-scale iron rule: a 200-hop collection paints the scroll window
 * only, plus an instant search box (SE rule: the flow pane filters
 * itself);
 * 4. orchestration markers ride the ALREADY loaded request rows
 * (preconditionJson/loopJson joined by requestId — zero new requests,
 * summaries produced by the shared utils/orchestrationSummary kernel);
 * 5. dependency chords render inline: producer-seq badge reusing the
 * runSet depFrom/depForward key family, forward reference stays the
 * warning colour (producerEarlier === false);
 * 6. honest absence: preview failure shows the plain-word banner, empty
 * collection shows the guiding empty state — never a blank pane;
 * 7. i18n dual track: zh-CN and en-US carry the identical apitest.flow
 * key set; the zh side keeps the naming ruling (the interface-set
 * compound word everywhere, no bare standalone "set");
 * 8. the summary kernel is new code — comments carry zero CJK (open-source
 * English-comment rule) and it never throws on malformed jsonb text.
 */
import { describe, expect, it } from 'vitest'

import viewRaw from '@/views/apitest/ApiTestView.vue?raw'
import summaryRaw from '@/utils/orchestrationSummary.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

/** segment the `flow: { ... }` block out of an apitest locale section. */
function flowBlockKeys(src: string): string[] {
  const start = src.indexOf('    flow: {')
  expect(start, 'apitest.flow locale block missing').toBeGreaterThan(-1)
  const end = src.indexOf('\n    },', start)
  expect(end, 'apitest.flow locale block unterminated').toBeGreaterThan(start)
  return src
    .slice(start, end)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => /^[A-Za-z]\w*:/.test(line))
    .map((line) => line.slice(0, line.indexOf(':')))
    .sort()
}

describe('T414 3-b: rail browse/flow toggle', () => {
  it('mounts the two-state toggle inside the request section', () => {
    expect(viewRaw).toContain('data-test="apitest-flow-toggle"')
    expect(viewRaw).toContain("t('apitest.flow.viewBrowse')")
    expect(viewRaw).toContain("t('apitest.flow.viewFlow')")
  })

  it('browse and flow panes are mutually exclusive over one row source', () => {
    expect(viewRaw).toMatch(/railView === 'flow'/)
    expect(viewRaw).toMatch(/railView === 'browse'/)
  })

  it('never draws a second tab bar outside the rail (single switch shape)', () => {
    const toggleCount = viewRaw.match(/data-test="apitest-flow-toggle"/g)?.length ?? 0
    expect(toggleCount).toBe(1)
  })
})

describe('T414 3-b: flow rows are the execution truth, windowed', () => {
  it('sources the flow pane from the launch preview (画的即跑 same source)', () => {
    expect(viewRaw).toMatch(/import \{[^}]*previewRunSet[^}]*\} from '@\/api\/apiTest'/)
    expect(viewRaw).toMatch(/flowSteps = await previewRunSet|await previewRunSet\(collectionId\.value\)/)
  })

  it('windowes the flow rows with the shared virtualiser (worst-scale iron rule)', () => {
    expect(viewRaw).toMatch(/flowWindow = useWindowedList/)
    expect(viewRaw).toContain('data-test="apitest-flow-rows"')
    expect(viewRaw).toContain('data-test="apitest-flow-search"')
  })

  it('carries an honest failure banner and an empty-collection guide (no blank pane)', () => {
    expect(viewRaw).toContain("t('apitest.flow.loadFail')")
    expect(viewRaw).toContain("t('apitest.flow.empty')")
  })
})

describe('T414 3-b: orchestration markers + inline dependency chords', () => {
  it('joins markers from the already-loaded rows via the shared summary kernel', () => {
    expect(viewRaw).toMatch(/preconditionSummary/)
    expect(viewRaw).toMatch(/loopSummary/)
    expect(viewRaw).toContain('data-test="apitest-flow-pre-tag"')
    expect(viewRaw).toContain('data-test="apitest-flow-loop-tag"')
  })

  it('dependency badges reuse the runSet dep key family, forward ref stays warning', () => {
    expect(viewRaw).toContain("t('apitest.runSet.depFrom'")
    expect(viewRaw).toContain("t('apitest.runSet.depForward'")
    expect(viewRaw).toMatch(/producerEarlier \? 'info' : 'warning'/)
  })

  it('no inline svg canvas this slice (recon ruling: overlay chords are phase 2)', () => {
    const flowPane = viewRaw.slice(viewRaw.indexOf('railView === \'flow\''))
    const section = flowPane.slice(0, flowPane.indexOf('apitest-workspace'))
    expect(section).not.toContain('<svg')
  })
})

describe('T414 3-b: summary kernel shape + open-source comment rule', () => {
  it('exports the two summarizers as a standalone util (no inner-class style blob)', () => {
    expect(summaryRaw).toMatch(/export function preconditionSummary/)
    expect(summaryRaw).toMatch(/export function loopSummary/)
  })

  it('new-code comments carry zero CJK (English capability sentences)', () => {
    expect(summaryRaw).not.toMatch(/[\u4e00-\u9fff]/)
  })
})

describe('T414 3-b: i18n dual track + naming ruling', () => {
  it('zh and en carry the identical apitest.flow key set', () => {
    expect(flowBlockKeys(zhRaw)).toEqual(flowBlockKeys(enRaw))
    expect(flowBlockKeys(zhRaw).length).toBeGreaterThanOrEqual(8)
  })

  it('zh flow strings keep the interface-set word (zero bare 集合, T413 ruling)', () => {
    const start = zhRaw.indexOf('    flow: {')
    const end = zhRaw.indexOf('\n    },', start)
    const block = zhRaw.slice(start, end)
    const withoutCompound = block.replace(/接口集/g, '')
    expect(withoutCompound).not.toContain('集合')
  })
})

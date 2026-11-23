/**
 * step-table windowed body + bulk operation suite (RED first).
 *
 * Defect closed: the wizard step table rendered every step inline
 * (`v-for ... in form.steps`, worst scale 500 steps) and offered only
 * single-row delete, while the scale contract requires the same
 * windowed + bulk treatment the import panel already got. The auxiliary
 * editor state (folds, header drafts, token target) is index-keyed — the
 * known shift trap: any delete/insert/reorder that forgets to remap it
 * makes the next row show the previous row's half-typed header.
 *
 * Contract asserted here:
 * 1. the step body renders through useWindowedList with pads — never a
 * page-level full v-for of all steps;
 * 2. the v-for key is the stable per-row uid, not the array index;
 * 3. bulk selection is keyed by row uid (survives filtering and reordering);
 * 4. aux-state remap helpers are pure and unit-tested (shrink / grow / swap /
 * duplicate), because every bulk operation must keep folds+drafts+token
 * target aligned with the rows they belong to;
 * 5. bulk delete = count-named confirmation + one-step undo (
 * snapshot discipline);
 * 6. row operations: insert-after, duplicate with parameters (deep copy),
 * move up/down;
 * 7. instant step search (method/url/link name/action) with the select-all
 * scoped to the CURRENT FILTER RESULT;
 * 8. the field label points at the always-rendered search box (a label `for`
 * an id inside the window/filter could dangle = browser Issues violation).
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import {
  duplicateStepRow,
  growStepAux,
  shrinkStepAux,
  stepUidOf,
  swapStepAux,
  type StepAux,
} from '@/views/perf/stepTableOps'

function aux(open: boolean[], drafts: Record<number, { k: string; v: string }>, target: { i: number; slot: string } | null): StepAux<string> {
  return { open, drafts, target }
}

describe('step-table aux remap helpers (index-shift trap)', () => {
  it('shrink removes the position and shifts everything behind it up', () => {
    const a = aux([true, false, true], { 2: { k: 'X', v: 'y' } }, { i: 2, slot: 'url' })
    shrinkStepAux(a, 1)
    expect(a.open).toEqual([true, true])
    expect(a.drafts[1]).toEqual({ k: 'X', v: 'y' })
    expect(a.drafts[2]).toBeUndefined()
    expect(a.target).toEqual({ i: 1, slot: 'url' })
  })

  it('shrink drops the token target when it pointed at the removed row', () => {
    const a = aux([true, true], {}, { i: 1, slot: 'body' })
    shrinkStepAux(a, 1)
    expect(a.target).toBeNull()
  })

  it('grow inserts a closed fold at the position and shifts everything from it down', () => {
    const a = aux([true, false], { 1: { k: 'A', v: 'b' } }, { i: 1, slot: 'saveAs' })
    growStepAux(a, 1)
    expect(a.open).toEqual([true, false, false])
    expect(a.drafts[2]).toEqual({ k: 'A', v: 'b' })
    expect(a.target).toEqual({ i: 2, slot: 'saveAs' })
  })

  it('swap exchanges folds, drafts and follows the token target', () => {
    const a = aux([true, false], { 0: { k: 'A', v: '1' } }, { i: 0, slot: 'url' })
    swapStepAux(a, 0, 1)
    expect(a.open).toEqual([false, true])
    expect(a.drafts[1]).toEqual({ k: 'A', v: '1' })
    expect(a.drafts[0]).toBeUndefined()
    expect(a.target).toEqual({ i: 1, slot: 'url' })
  })

  it('duplicate deep-copies parameters with a fresh row identity', () => {
    const row = { action: 'http.request', method: 'POST', url: '/a', headers: { 'X-A': '1' }, body: '{"k":1}', expect: { status: 200 }, saveAs: 'tok' }
    const copy = duplicateStepRow(row)
    expect(copy).not.toBe(row)
    expect(copy.headers).not.toBe(row.headers)
    expect(copy.headers).toEqual(row.headers)
    expect(copy.expect).not.toBe(row.expect)
    ;(copy.headers as Record<string, string>)['X-A'] = '2'
    expect((row.headers as Record<string, string>)['X-A']).toBe('1')
  })

  it('row uid is stable per object identity and unique per row', () => {
    const r1 = { action: 'a' }
    const r2 = { action: 'b' }
    expect(stepUidOf(r1)).toBe(stepUidOf(r1))
    expect(stepUidOf(r1)).not.toBe(stepUidOf(r2))
  })
})

describe('step-table windowed layout contract (raw anchors)', () => {
  it('the step body renders through the windowed slice with pads', () => {
    expect(wizardRaw).toMatch(/const stepWindow = useWindowedList\(/)
    expect(wizardRaw).toContain('stepPadTop')
    expect(wizardRaw).toContain('stepPadBottom')
    expect(wizardRaw).toContain('stepListEl')
    expect(wizardRaw).toMatch(/onStepListScroll/)
  })

  it('no page-level full v-for of all steps remains', () => {
    expect(wizardRaw).not.toMatch(/v-for="\(row, i\) in form\.steps"/)
  })

  it('rows key by stable uid and carry the windowed-row marker', () => {
    expect(wizardRaw).toMatch(/:key="entry\.uid"/)
    // the step block itself is the measured row
    const block = wizardRaw.slice(wizardRaw.indexOf('pw__step-block'), wizardRaw.indexOf('pw__step-block') + 400)
    expect(block).toContain('data-windowed-row')
  })

  it('bulk selection keys by row uid, never by array index', () => {
    expect(wizardRaw).toMatch(/const stepSelected = ref<Set<number>>\(/)
    expect(wizardRaw).toContain('stepUidOf')
  })

  it('instant step search and filter-scoped select-all exist', () => {
    expect(wizardRaw).toMatch(/const stepKeyword = ref\(''\)/)
    expect(wizardRaw).toMatch(/const filteredStepEntries = computed/)
    expect(wizardRaw).toContain('data-test="pw-step-search"')
    expect(wizardRaw).toContain('data-test="pw-step-select-all"')
    expect(wizardRaw).toContain('data-test="pw-step-bulk-delete"')
    expect(wizardRaw).toContain('data-test="pw-step-bulk-undo"')
  })

  it('per-row operations: insert-after, duplicate, move up/down', () => {
    for (const a of ['pw-step-insert-after', 'pw-step-duplicate', 'pw-step-move-up', 'pw-step-move-down']) {
      expect(wizardRaw).toContain(`data-test="${a}"`)
    }
  })

  it('bulk delete asks with a count-named confirmation and undoes one step', () => {
    const fn = wizardRaw.slice(wizardRaw.indexOf('function bulkDeleteSteps'), wizardRaw.indexOf('function bulkDeleteSteps') + 900)
    expect(fn).toContain('ElMessageBox.confirm')
    expect(fn).toContain('stepBulkDeleteConfirm')
    expect(fn).toContain('bulkUndoSnapshot')
  })

  it('the field label points at the always-rendered search box (no dangling for)', () => {
    expect(wizardRaw).toContain('<label for="pw-step-search">')
  })

  it('i18n both tracks carry the bulk-operation vocabulary', () => {
    for (const key of [
      'stepSearchPh',
      'stepSelectAll',
      'stepSelectedCount',
      'stepBulkDelete',
      'stepBulkDeleteConfirm',
      'stepBulkDeleted',
      'stepBulkUndo',
      'stepBulkUndone',
      'stepInsertAfter',
      'stepDuplicate',
      'stepMoveUp',
      'stepMoveDown',
      'stepNoMatch',
    ]) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
  })
})

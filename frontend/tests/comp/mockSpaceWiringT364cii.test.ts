/**
 * ii: mock-space binding wiring contract for the two editors.
 *
 * CaseDetailView edit form and the SuiteListView create/edit dialog must both
 * host <MockSpacePicker /> (single shared component — no second implementation,
 * red line 6) and carry the value through the save payload with the declared
 * three-state semantics the core gate owns:
 * - untouched -> null (no update to the binding)
 * - (re)binding -> the picked space id
 * - unbinding -> 0 ("run against the real environment" sentinel)
 * Prefill on entering edit mode reads the view's mockSpaceId back into the
 * picker, so a saved binding survives reload (persistence asserted end-to-end
 * by the segment's first E2E, not here).
 */
import { describe, expect, it } from 'vitest'

import caseRaw from '@/views/case/CaseDetailView.vue?raw'
import suiteRaw from '@/views/suite/SuiteListView.vue?raw'

const PICKER_IMPORT = "import MockSpacePicker from '@/components/MockSpacePicker.vue'"

describe('CaseDetailView mock-space wiring', () => {
  it('imports the shared MockSpacePicker component', () => {
    expect(caseRaw).toContain(PICKER_IMPORT)
  })

  it('renders MockSpacePicker bound to editForm.mockSpaceId in the edit form', () => {
    expect(caseRaw).toMatch(/<MockSpacePicker[^>]*v-model="editForm\.mockSpaceId"/)
  })

  it('editForm declares the mockSpaceId field', () => {
    expect(caseRaw).toMatch(/mockSpaceId: number \| null/)
  })

  it('prefills the binding from the loaded case view', () => {
    expect(caseRaw).toMatch(/editForm\.mockSpaceId = d\.mockSpaceId \?\? null/)
  })

  it('saveDraft sends the three-state binding payload', () => {
    // untouched -> null (no update); no-mock chosen -> 0 (unbind sentinel);
    // a space id -> rebind. Same posture as the appauto profile column.
    expect(caseRaw).toContain('const mockSpaceChanged = editForm.mockSpaceId !== (detail.value.mockSpaceId ?? null)')
    expect(caseRaw).toContain('mockSpaceId: mockSpaceChanged ? editForm.mockSpaceId ?? 0 : null')
  })
})

describe('SuiteListView mock-space wiring', () => {
  it('imports the shared MockSpacePicker component', () => {
    expect(suiteRaw).toContain(PICKER_IMPORT)
  })

  it('renders MockSpacePicker bound to form.mockSpaceId in the dialog', () => {
    expect(suiteRaw).toMatch(/<MockSpacePicker[^>]*v-model="form\.mockSpaceId"/)
  })

  it('form declares and resets the mockSpaceId field consistently', () => {
    expect(suiteRaw).toMatch(/mockSpaceId: number \| null/)
    // both reset sites (openCreate path uses Object.assign defaults) keep the
    // key present so the reactive shape never shrinks
    const resets = suiteRaw.match(/mockSpaceId: null/g) ?? []
    expect(resets.length).toBeGreaterThanOrEqual(2)
  })

  it('openEdit prefills the row binding', () => {
    expect(suiteRaw).toMatch(/mockSpaceId: row\.mockSpaceId \?\? null/)
  })

  it('create and update payloads both carry the binding', () => {
    // The dialog is a full write: the picker's null ("no mock") must go out as
    // the 0 unbind sentinel — null on update means "untouched" and would
    // silently keep a stale binding.
    const bind = /mockSpaceId: form\.mockSpaceId \?\? 0/
    const createAt = suiteRaw.indexOf('await createSuite(')
    const updateAt = suiteRaw.indexOf('await updateSuite(')
    expect(createAt).toBeGreaterThan(-1)
    expect(updateAt).toBeGreaterThan(-1)
    const createBlock = suiteRaw.slice(createAt, suiteRaw.indexOf('}', suiteRaw.indexOf('status: form.status', createAt)))
    expect(createBlock).toMatch(bind)
    const updateBlock = suiteRaw.slice(updateAt, suiteRaw.indexOf('}', suiteRaw.indexOf('status: form.status', updateAt)))
    expect(updateBlock).toMatch(bind)
  })
})

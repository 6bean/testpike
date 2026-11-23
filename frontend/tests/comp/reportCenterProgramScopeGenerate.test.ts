/**
 * Report-center program-dimension mirror — real-mount behaviour
 * contract (Vitest Browser Mode, real Chromium). Assembly comes from
 * tests/comp/lensWiringHarness.ts (R40 single source, admin option added);
 * this file contributes only the page's own API mocks and the DOM driving.
 *
 * Contracts pinned (frontend side of the scope_type=3 tier merged as
 * f1450de26 — backend requireType(1,3) + normalizeScope program branch):
 * 1. non-ADMIN sees neither the generate entry nor the scope filter
 * (the ADMIN gates at v-if keep the ledger honest for members);
 * 3. picking the program radio fills the program picker from
 * listPrograms and submitting sends {scopeType:3, scopeId:<program id>}
 * — program ids never travel in the project field (different key space);
 * 4. switching scope dims clears the stale target id and the project dim
 * falls back to the header context (onGenScopeTypeChange).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'

/** Captured list/generate payloads, in call order. */
const listCalls = vi.hoisted(() => [] as Record<string, unknown>[])
const generateCalls = vi.hoisted(() => [] as Record<string, unknown>[])
/** Two programs only — id 21/22 sit far from project ids (11/12) so a
 *  key-space mix-up cannot pass by coincidence. */
const PROGRAM_ALPHA = 21
const PROGRAM_BETA = 22

vi.mock('@/api/reportCenter', (io) => makeReportCenterDouble(io, { list: listCalls, generate: generateCalls }))
vi.mock('@/api/program', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/program')>()
  return {
    ...actual,
    listPrograms: async () =>
      [
        { id: PROGRAM_ALPHA, name: 'Program Alpha', description: null, state: 1, createdAt: '2026-01-01T00:00:00' },
        { id: PROGRAM_BETA, name: 'Program Beta', description: null, state: 1, createdAt: '2026-01-01T00:00:00' },
      ] as never,
  }
})
vi.mock('@/api/project', (io) => projectApiDouble(io))
vi.mock('@/api/report', (io) => reportApiDouble(io))

// Import order matters: the harness bindings must exist before the page
// module graph (which pulls '@/api/project') evaluates — hoisted vi.mock
// factories run at that point and reference the doubles.
import {
  LENS_PROJ_ID,
  makeReportCenterDouble,
  mountPageWithLens,
  projectApiDouble,
  reportApiDouble,
} from './lensWiringHarness'
import ReportCenterView from '@/views/report-center/ReportCenterView.vue'

function adminMount() {
  return mountPageWithLens({
    component: ReportCenterView,
    routePath: '/report-center',
    activeProgramId: null,
    admin: true,
    // The dialog-driving contracts need the live document: on a detached
    // mount EP hands back a stale dialog subtree whose listeners are gone
    // (probe 11-08: input checked but v-model never fired, node
    // isConnected=false). attachToBody mirrors the real page.
    attachToBody: true,
  })
}

/** EP renders the dialog in place (append-to-body is off), so resolve the
 *  panel inside the mounted subtree, not document.body. */
function generateDialog(wrapper: VueWrapper): HTMLElement {
  const sel = wrapper.find('[data-test="rc-generate-dialog"]').exists()
    ? '[data-test="rc-generate-dialog"]'
    : '.el-dialog'
  const found = wrapper.find(sel)
  expect(found.exists(), 'generate dialog should be rendered').toBe(true)
  return found.element as HTMLElement
}

async function openDialog(wrapper: Awaited<ReturnType<typeof adminMount>>['wrapper']) {
  await wrapper.find('[data-test="rc-generate-open"]').trigger('click')
  await flushPromises()
  await flushPromises()
}

async function pickRadio(group: HTMLElement, labelText: string): Promise<void> {
  const labels = Array.from(group.querySelectorAll('label')) as HTMLElement[]
  const hit = labels.find((l) => (l.textContent ?? '').includes(labelText))
  expect(hit, `radio "${labelText}" should exist`).toBeTruthy()
  // Drive the <label> (EP 2.9's visible click target), not the visually
  // hidden native input — mirrors what a user activates.
  hit!.click()
  await flushPromises()
}

/** Open an el-select (EP 2.9 renders the click target as .el-select__wrapper). */
async function openSelect(root: Element, testId: string): Promise<void> {
  const sel = root.querySelector(`[data-test="${testId}"]`) as HTMLElement | null
  expect(sel, `select [data-test=${testId}] should exist`).toBeTruthy()
  const trigger = (sel!.querySelector('.el-select__wrapper') ?? sel!) as HTMLElement
  trigger.click()
  await flushPromises()
}

/** Click the visible dropdown entry whose text contains `label`. */
async function clickOption(label: string): Promise<void> {
  const items = Array.from(document.querySelectorAll('.el-select-dropdown__item')) as HTMLElement[]
  const hit = items.filter((i) => (i.textContent ?? '').includes(label)).pop()
  expect(hit, `option "${label}" should be rendered`).toBeTruthy()
  hit!.click()
  await flushPromises()
}

/** Type both range inputs and confirm with Enter (value-format=YYYY-MM-DD). */
async function typePeriod(start: string, end: string): Promise<void> {
  for (const [id, value] of [['rc-gen-period-start', start], ['rc-gen-period-end', end]] as const) {
    const input = document.getElementById(id) as HTMLInputElement | null
    expect(input, `period input ${id} should exist`).toBeTruthy()
    input!.focus()
    input!.value = value
    input!.dispatchEvent(new Event('input', { bubbles: true }))
    input!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    input!.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    await flushPromises()
  }
}

async function submitDialog(wrapper: VueWrapper): Promise<void> {
  const btn = generateDialog(wrapper).querySelector('[data-test="rc-generate-submit"]') as HTMLElement
  btn.click()
  await flushPromises()
  await flushPromises()
}

describe('report-center program-scope generate (MP5b)', () => {
  beforeEach(() => {
    listCalls.length = 0
    generateCalls.length = 0
    document.body.innerHTML = ''
  })

  it('non-ADMIN: no generate entry, no scope filter', async () => {
    const { wrapper } = await mountPageWithLens({
      component: ReportCenterView,
      routePath: '/report-center',
      activeProgramId: null,
    })
    expect(wrapper.find('[data-test="rc-generate-open"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="rc-scope-type"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('ADMIN scope filter offers the program tier (3)', async () => {
    const { wrapper } = await adminMount()
    expect(wrapper.find('[data-test="rc-scope-type"]').exists()).toBe(true)
    await openSelect(wrapper.element, 'rc-scope-type')
    const items = Array.from(document.querySelectorAll('.el-select-dropdown__item')) as HTMLElement[]
    const texts = items.map((i) => i.textContent ?? '')
    expect(texts.some((s) => s.includes('项目维'))).toBe(true)
    expect(texts.some((s) => s.includes('全局维'))).toBe(true)
    expect(texts.some((s) => s.includes('项目集维'))).toBe(true)
    wrapper.unmount()
  })

  it('program radio + picker: submit carries scopeType=3 and the program id', async () => {
    const { wrapper } = await adminMount()
    await openDialog(wrapper)
    const dlg = generateDialog(wrapper)
    await pickRadio(dlg.querySelector('[data-test="rc-gen-scope-type"]') as HTMLElement, '项目集维')
    expect(dlg.querySelector('[data-test="rc-gen-program"]')).toBeTruthy()
    await openSelect(dlg, 'rc-gen-program')
    await clickOption('Program Alpha')
    await typePeriod('2026-01-05', '2026-01-11')
    await submitDialog(wrapper)
    expect(generateCalls.length).toBe(1)
    expect(generateCalls[0].scopeType).toBe(3)
    expect(generateCalls[0].scopeId).toBe(PROGRAM_ALPHA)
    expect(generateCalls[0].periodStart).toBe('2026-01-05T00:00')
    expect(generateCalls[0].periodEnd).toBe('2026-01-12T00:00')
    wrapper.unmount()
  })

  it('dim switch clears the stale target id; project dim falls back to header context', async () => {
    const { wrapper } = await adminMount()
    await openDialog(wrapper)
    const dlg = generateDialog(wrapper)
    const group = dlg.querySelector('[data-test="rc-gen-scope-type"]') as HTMLElement
    await pickRadio(group, '项目集维')
    await openSelect(dlg, 'rc-gen-program')
    await clickOption('Program Beta')
    // Back to the project dim: the program id (22) must NOT survive as a project id.
    await pickRadio(group, '项目维')
    await typePeriod('2026-02-02', '2026-02-08')
    await submitDialog(wrapper)
    expect(generateCalls.length).toBe(1)
    expect(generateCalls[0].scopeType).toBe(1)
    expect(generateCalls[0].scopeId).toBe(LENS_PROJ_ID)
    wrapper.unmount()
  })
})

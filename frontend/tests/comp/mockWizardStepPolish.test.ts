/**
 * Wizard step-rail polish contract (vision rubric mid-severity batch):
 *  1. Step 1 upload must NOT expose the browser-default file widget (its
 *     "Choose File" label renders in the browser's own language and cannot be
 *     localized). The app draws a localized pick button and echoes the chosen
 *     file name in Chinese/English; the real <input type=file> stays in the
 *     DOM (hidden) so automation can still feed it directly.
 *  2. The dialog must grow monotonically: md at step 1, lg from step 2 onward
 *     and it must stay lg at the receipt step. Growing then shrinking back
 *     made the whole dialog jump across the viewport between clicks.
 *  3. When the receipt step is shown the import has already finished, so the
 *     step rail must mark ALL three steps as done — the last step rendering
 *     as a hollow "still in progress" circle contradicted the receipt text.
 *  4. The dark-theme current-step highlight lives in the global theme file
 *     (single source), not in this component's scoped styles.
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElTable } from 'element-plus'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const mockApi = vi.hoisted(() => ({
  listSpaces: vi.fn(),
  previewStubDraftsAdvised: vi.fn(),
  fetchSpecUpgradePlan: vi.fn(),
  markRemovedUpgradePlan: vi.fn(),
  importStubsFromSpec: vi.fn(),
}))
vi.mock('@/api/mock', () => mockApi)

const specApi = vi.hoisted(() => ({ listApiSpecs: vi.fn(), uploadApiSpec: vi.fn() }))
vi.mock('@/api/apiSpec', () => specApi)

import wizardRaw from '@/components/mock/MockStubDirectWizard.vue?raw'
import themeRaw from '@/styles/theme.scss?raw'
import MockStubDirectWizard from '@/components/mock/MockStubDirectWizard.vue'
import { wizardDraftRow, wizardSpecRow } from './wizardFixtures'

const specRow = wizardSpecRow
const draftRow = wizardDraftRow

function spaceRow(over: Record<string, unknown> = {}) {
  return {
    id: 9, projectId: 3, name: '空间九', spaceKey: 'k9', alias: '',
    envId: null, basePathMode: 'prefix', status: 1, ...over,
  }
}

async function open(props: Record<string, unknown> = {}) {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const w = mount(MockStubDirectWizard, {
    props: { modelValue: false, projectId: 3, ...props },
    global: { plugins: [ElementPlus, i18n, createPinia()] },
  })
  await w.setProps({ modelValue: true })
  await flushPromises()
  return w
}

async function toStep2(w: ReturnType<typeof mount>) {
  w.findComponent(ElTable).vm.$emit('row-click', specRow)
  await flushPromises()
  const btn = w.find('[data-test="stub-direct-preview-btn"]')
  expect(btn.exists(), 'preview button must be rendered in step 1').toBe(true)
  await btn.trigger('click')
  await flushPromises()
}

async function toStep3(w: ReturnType<typeof mount>) {
  const btn = w.find('[data-test="stub-direct-import-btn"]')
  expect(btn.exists(), 'import button must be rendered in step 2').toBe(true)
  await btn.trigger('click')
  await flushPromises()
}

describe('wizard upload: localized pick button + file name echo', () => {
  beforeEach(() => {
    specApi.listApiSpecs.mockReset().mockResolvedValue({ list: [specRow], total: 1 })
    specApi.uploadApiSpec.mockReset()
    mockApi.listSpaces.mockReset().mockResolvedValue([spaceRow()])
    mockApi.previewStubDraftsAdvised.mockReset().mockResolvedValue([draftRow])
    // First-version contract posture: empty upgrade plan keeps the wizard on
    // the selection step (the difference step is contract-tested elsewhere).
    mockApi.fetchSpecUpgradePlan.mockReset()
      .mockResolvedValue({ added: [], changed: [], removed: [], hasPrevious: false })
    mockApi.markRemovedUpgradePlan.mockReset().mockResolvedValue(0)
    mockApi.importStubsFromSpec.mockReset()
      .mockResolvedValue({ createdStubIds: [77], skippedEndpointKeys: [] })
  })

  it('upload panel swaps the browser-default widget for an app button and keeps the input hidden in DOM', async () => {
    const w = await open({ presetSpaceId: null, presetSpaceName: '' })
    // switch the source radio to upload
    const radios = w.findAll('[data-test="stub-direct-source-radio"] input[type="radio"]')
    expect(radios.length, 'source radio group must render two radios').toBe(2)
    await radios[1].setValue()
    await flushPromises()
    expect(w.find('[data-test="stub-direct-upload-panel"]').exists(), 'upload panel must show').toBe(true)

    const pickBtn = w.find('[data-test="stub-direct-pick-file"]')
    expect(pickBtn.exists(), 'app-localized pick button must exist').toBe(true)
    expect(pickBtn.text().length, 'pick button must carry its own label').toBeGreaterThan(0)

    const input = w.find('[data-test="stub-direct-file-input"]')
    expect(input.exists(), 'the real file input stays in the DOM for automation').toBe(true)
    expect(input.classes()).toContain('sd-file-hidden')

    // no file yet -> localized placeholder line, not the browser default text
    const none = w.find('[data-test="stub-direct-file-none"]')
    expect(none.exists(), 'empty-file placeholder line must render').toBe(true)
    expect(none.text()).not.toContain('Choose file')

    // feeding a file through the same change path echoes the name
    const file = new File(['{}'], 'openapi-orders.yaml', { type: 'application/x-yaml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    const echo = w.find('[data-test="stub-direct-file-name"]')
    expect(echo.exists(), 'chosen-file echo line must render after change').toBe(true)
    expect(echo.text()).toContain('openapi-orders.yaml')
    // the pick action also forwards to the hidden input so the flow stays 1-click
    expect(wizardRaw).toContain('stub-direct-pick-file')
    expect(wizardRaw).toContain('.click()')
  })

  it('a finished upload chains straight into the advised preview when the space is already known', async () => {
    // Click budget for the upload branch of the key enable journey:
    // entry / pick source / file / upload / one-click enable must stay within
    // four deliberate page clicks (the ruling names the nodes entry-button,
    // pick-the-contract, see-the-advised-set, one-click-enable). Without the
    // chain, a manual preview click after upload is a hidden fifth.
    specApi.uploadApiSpec.mockResolvedValue(specRow)
    const w = await open({ presetSpaceId: 9, presetSpaceName: '空间九' })
    const radios = w.findAll('[data-test="stub-direct-source-radio"] input[type="radio"]')
    await radios[1].setValue()
    await flushPromises()
    const input = w.find('[data-test="stub-direct-file-input"]')
    const file = new File(['{}'], 'openapi-chain.yaml', { type: 'application/x-yaml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    await w.find('[data-test="stub-direct-upload-btn"]').trigger('click')
    await flushPromises()

    expect(specApi.uploadApiSpec, 'the upload click persists the contract row').toHaveBeenCalledTimes(1)
    // No preview-button click happened in between; the advised-set fetch must
    // already have run and the selection step must already be on screen.
    expect(mockApi.previewStubDraftsAdvised,
      'upload success must auto-run the advised preview (no hidden fifth click)').toHaveBeenCalledTimes(1)
    expect(mockApi.previewStubDraftsAdvised).toHaveBeenCalledWith(21, 9)
    expect(w.find('[data-test="stub-direct-import-btn"]').exists(),
      'the enable door is visible right after the upload click').toBe(true)
  })

  it('upload success stays on step 1 when no destination space is resolved', async () => {
    // Contract-list mount posture: the space still has to be picked, so the
    // chain must be a silent no-op there — the receipt shows and the preview
    // door stays available for the manual click, exactly as before.
    specApi.uploadApiSpec.mockResolvedValue(specRow)
    const w = await open({ presetSpaceId: null, presetSpaceName: '' })
    const radios = w.findAll('[data-test="stub-direct-source-radio"] input[type="radio"]')
    await radios[1].setValue()
    await flushPromises()
    const input = w.find('[data-test="stub-direct-file-input"]')
    const file = new File(['{}'], 'openapi-nopace.yaml', { type: 'application/x-yaml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    await w.find('[data-test="stub-direct-upload-btn"]').trigger('click')
    await flushPromises()

    expect(specApi.uploadApiSpec).toHaveBeenCalledTimes(1)
    expect(mockApi.previewStubDraftsAdvised,
      'no space resolved -> no preview request may leave the wizard').not.toHaveBeenCalled()
    expect(w.find('[data-test="stub-direct-upload-done"]').exists(),
      'the upload receipt stays visible on step 1').toBe(true)
    expect(w.find('[data-test="stub-direct-preview-btn"]').exists(),
      'the preview door stays for the manual click').toBe(true)
  })
})

describe('wizard dialog: width grows monotonically, receipt step closes the rail', () => {
  beforeEach(() => {
    specApi.listApiSpecs.mockReset().mockResolvedValue({ list: [specRow], total: 1 })
    mockApi.listSpaces.mockReset().mockResolvedValue([spaceRow()])
    mockApi.previewStubDraftsAdvised.mockReset().mockResolvedValue([draftRow])
    // First-version contract posture: empty upgrade plan keeps the wizard on
    // the selection step (the difference step is contract-tested elsewhere).
    mockApi.fetchSpecUpgradePlan.mockReset()
      .mockResolvedValue({ added: [], changed: [], removed: [], hasPrevious: false })
    mockApi.markRemovedUpgradePlan.mockReset().mockResolvedValue(0)
    mockApi.importStubsFromSpec.mockReset()
      .mockResolvedValue({ createdStubIds: [77], skippedEndpointKeys: [] })
  })

  it('step 1 is md, steps 2 and 3 are both lg (no shrink between clicks)', async () => {
    const w = await open({ presetSpaceId: 9, presetSpaceName: '空间九' })
    expect(w.find('.el-dialog.tp-dialog--md').exists(), 'step 1 opens at md width').toBe(true)
    await toStep2(w)
    expect(w.find('.el-dialog.tp-dialog--lg').exists(), 'step 2 grows to lg').toBe(true)
    await toStep3(w)
    expect(w.find('.el-dialog.tp-dialog--lg').exists(), 'receipt step must STAY lg — shrinking back makes the dialog jump').toBe(true)
    expect(w.find('.el-dialog.tp-dialog--md').exists(), 'md must be gone once grown').toBe(false)
  })

  it('receipt step marks all three rail steps as done (no hollow trailing circle)', async () => {
    const w = await open({ presetSpaceId: 9, presetSpaceName: '空间九' })
    await toStep2(w)
    await toStep3(w)
    const heads = w.findAll('.el-step__head')
    expect(heads.length).toBe(3)
    for (const h of heads) {
      expect(h.classes(), `step head must be in the done (is-success) state, got ${h.classes().join(' ')}`)
        .toContain('is-success')
    }
  })
})

describe('theme single source for the step rail', () => {
  it('global theme file styles the current (process) step explicitly for both palettes', () => {
    // The dark palette showed no difference between the current step and the
    // upcoming ones — the fix lives once in theme.scss, scoped copies in pages
    // are banned by the same rule as the focus-ring single source.
    expect(themeRaw).toMatch(/\.el-step__head\.is-process/)
    expect(themeRaw).toMatch(/\.el-step__title\.is-process/)
    expect(themeRaw).toMatch(/\.el-steps[^{]*html:not\(\.dark\)|html\.dark[^{]*\.el-step|\.el-step__head\.is-process/)
    // scoped wizard styles must not grow their own step-rail copy
    expect(wizardRaw).not.toMatch(/<style[\s\S]*\.el-step/)
  })

  it('locale dual track defines the pick-button / echo / placeholder sentences', () => {
    const zh = (zhCN as any).apitest.stubImport
    const en = (enUS as any).apitest.stubImport
    for (const pack of [zh, en]) {
      expect(typeof pack.pickFileBtn).toBe('string')
      expect(pack.pickFileBtn.length).toBeGreaterThan(0)
      expect(pack.filePicked).toContain('{name}')
      expect(typeof pack.noFileHint).toBe('string')
      expect(pack.noFileHint.length).toBeGreaterThan(0)
    }
  })
})

/**
 * Three-branch empty-state copy resolver for the header program lens
 * . When a page's list comes back empty the
 * user must be told WHY in operational terms: no rows inside the picked
 * program segment, no rows under the active filters, or genuinely nothing to
 * show yet. Writing that branch table per page is exactly the duplication
 * the gate keeps out, so it lives once in useProgramLens and every lens
 * page consumes it.
 *
 * Branch contract pinned here:
 * 1. lens ON, no filters → page-owned title, shared program-slice hint
 * (points at the header switcher, never at an in-page picker — R30);
 * 2. lens ON with filters → same title, filter hint + clear-filter exit;
 * 3. lens OFF → legacy keys byte-for-byte (zero drift for
 * users who never pick a program).
 */
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import zhCN from '@/utils/locales/zh-CN'
import { useProgramContextStore } from '@/stores/programContext'
import { lensEmptyCopy } from '@/composables/useProgramLens'

type LensCopy = ReturnType<typeof lensEmptyCopy>

/**
 * Run the resolver inside a component setup with a fresh pinia + the real
 * zh-CN catalog. The mount is what gives inject() a running app context;
 * the captured result is asserted by each case.
 */
function resolveCopy(opts: {
  titleKey: string
  emptyKey: string
  filtered?: boolean
  activeProgramId?: number | null
}): LensCopy {
  const pinia = createPinia()
  setActivePinia(pinia)
  if (opts.activeProgramId != null) useProgramContextStore(pinia).select(opts.activeProgramId)
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  let captured: LensCopy | null = null
  const Probe = defineComponent({
    setup() {
      captured = lensEmptyCopy({
        translate: (k: string) => i18n.global.t(k),
        titleKey: opts.titleKey,
        emptyKey: opts.emptyKey,
        filtered: opts.filtered ?? false,
      })
      return {}
    },
    template: '<div/>',
  })
  mount(Probe, { global: { plugins: [pinia, i18n] } })
  expect(captured).not.toBe(null)
  return captured as LensCopy
}

describe('lensEmptyCopy three-branch resolver (MP4-d)', () => {
  it('lens ON without filters: program-slice hint, no clear-filter exit', () => {
    const copy = resolveCopy({
      titleKey: 'defect.lensEmptyTitle',
      emptyKey: 'common.emptyNoData',
      activeProgramId: 7,
    })
    expect(copy.lensActive).toBe(true)
    expect(copy.title).toBe('当前项目集内暂无缺陷')
    expect(copy.hint).toContain('项目集')
    // Exit-guide wording is copy-owned (it already moved once); pin the
    // durable pointer at the header switcher, not the whole sentence.
    expect(copy.hint).toContain('顶栏项目集切换器')
    expect(copy.filtered).toBe(false)
  })

  it('lens OFF: legacy keys verbatim (zero drift)', () => {
    const copy = resolveCopy({ titleKey: 'defect.lensEmptyTitle', emptyKey: 'common.emptyNoData' })
    expect(copy.lensActive).toBe(false)
    expect(copy.title).toBe('暂无数据，点击下方按钮创建')
  })

  it('lens ON + filters: filter hint carries the clear-filter exit', () => {
    const copy = resolveCopy({
      titleKey: 'defect.lensEmptyTitle',
      emptyKey: 'common.emptyNoData',
      filtered: true,
      activeProgramId: 7,
    })
    expect(copy.lensActive).toBe(true)
    expect(copy.filtered).toBe(true)
    expect(copy.title).toBe('当前项目集内暂无缺陷')
    expect(copy.hint).toBe('试试调整或清空筛选条件')
  })
})

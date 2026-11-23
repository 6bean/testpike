/**
 * DataTablePage table-baseline contract ( of the site-wide table UX batch).
 * The shared shell must give every consumer page the CaseListView baseline
 * without per-page wiring: border always on (EP activates header column-width
 * dragging only with border), header-dragend writes the new width back into a
 * passed-in column-config object, and sort-change is re-emitted upward.
 * RED anchors (each mirrors a gap found on HEAD):
 * 1. no explicit border attr -> root still renders .el-table--border
 * 2. header-dragend -> columnConfig width updated + persisted
 * 3. internal sort-change -> surfaces as DataTablePage emitted event
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElTable } from 'element-plus'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import DataTablePage from '@/components/DataTablePage.vue'
import { useColumnConfig } from '@/composables/useColumnConfig'

const rows = [{ name: 'alpha' }, { name: 'beta' }]

function factory(props: Record<string, unknown> = {}) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  return mount(DataTablePage, {
    props: { data: rows, ...props },
    attachTo: host,
    global: {
      plugins: [
        ElementPlus,
        createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } }),
      ],
    },
  })
}

describe('DataTablePage table baseline (blade-1)', () => {
  it('renders border even when the attr is not passed (drag precondition)', () => {
    const w = factory()
    // EP puts .el-table--border on the root table wrapper when border=true
    expect(w.find('.el-table--border').exists()).toBe(true)
  })

  it('explicit border=false does not break rendering (consumer attrs still win)', () => {
    const w = factory({ border: true })
    expect(w.find('.el-table--border').exists()).toBe(true)
  })

  it('writes dragged column width back into columnConfig and localStorage', async () => {
    const defs = [{ key: 'name', labelKey: 't.name', width: 80 }]
    const colCfg = useColumnConfig('t292-contract', defs)
    const w = factory({ columnConfig: colCfg })
    w.findComponent(ElTable).vm.$emit('header-dragend', 320, 80, { property: 'name' })
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    const stored = colCfg.state.columns.find((c) => c.key === 'name')
    expect(stored?.width).toBe(320)
    const raw = localStorage.getItem('tp-colcfg-t292-contract')
    expect(raw).toContain('320')
  })

  it('ignores drag writeback when no columnConfig is provided', async () => {
    const w = factory()
    // must not throw when the page opted out of column config
    w.findComponent(ElTable).vm.$emit('header-dragend', 200, 80, { property: 'name' })
    await w.vm.$nextTick()
    expect(w.find('.el-table--border').exists()).toBe(true)
  })

  it('re-emits sort-change upward so pages keep one wiring point', async () => {
    const w = factory()
    w.findComponent(ElTable).vm.$emit('sort-change', { column: {}, prop: 'name', order: 'ascending' })
    await w.vm.$nextTick()
    const fired = w.emitted('sort-change')
    expect(fired).toBeTruthy()
    expect((fired![0][0] as { prop: string }).prop).toBe('name')
  })

  it('exposes the columnConfig so tooling/tests can read state through the ref', () => {
    const defs = [{ key: 'name', labelKey: 't.name', width: 80 }]
    const colCfg = useColumnConfig('t292-expose', defs)
    const w = factory({ columnConfig: colCfg })
    expect(w.vm.columnConfig).toBe(colCfg)
  })
})

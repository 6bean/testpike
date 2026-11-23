
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  pluginDetail: vi.fn(),
  pluginUpdate: vi.fn(),
  pluginAppliedProjects: vi.fn(),
}))
vi.mock('@/api/plugin', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  ...api,
}))

import PluginDetailDrawer from '@/views/plugin/PluginDetailDrawer.vue'
import { BizError } from '@/api/request'
import pluginApiRaw from '@/api/plugin?raw'
import drawerRaw from '@/views/plugin/PluginDetailDrawer.vue?raw'


function plugin(over: Record<string, unknown> = {}) {
  return {
    id: 41, name: 'pos-retail-custom', industry: 'POS', version: '1.0.0',
    enabled: true, source: 2, revision: 3,
    manifest: {
      name: 'pos-retail-custom', industry: 'POS', version: '1.0.0',
      content_pack: 'pos-pack', default_skills: ['收银规范'],
      param_template: { currency: 'TWD' }, project_types: ['pos'],
    },
    ...over,
  }
}

const mounted: { unmount: () => void }[] = []

function factory(props: Record<string, unknown>) {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const w = mount(PluginDetailDrawer, {
    props: { ...props, modelValue: false },
    global: { plugins: [ElementPlus, i18n] },
  })
  mounted.push(w)
  w.setProps({ modelValue: true })
  return { w, i18n }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.pluginDetail.mockResolvedValue(plugin())
  api.pluginAppliedProjects.mockResolvedValue([])
  api.pluginUpdate.mockResolvedValue(plugin({ revision: 4, version: '1.0.1' }))
})

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

describe('T242 P3 api/plugin.ts 契约镜像闸', () => {
  it('detail/PUT/applied-projects 三条链路与 admin PluginAdminController 对齐', () => {
    expect(pluginApiRaw).toMatch(/url: *`\/plugins\/\$\{[^}]+\}`,\s*method: 'get'/s)
    expect(pluginApiRaw).toMatch(/url: *`\/plugins\/\$\{[^}]+\}`,\s*method: 'put'/s)
    expect(pluginApiRaw).toContain('applied-projects')
    expect(pluginApiRaw).toContain('revision')
  })
})

describe('T242 P3 PluginDetailDrawer 查看态', () => {
  it('打开即拉 detail + applied-projects；描述符七键只读回显；装配项目行渲染', async () => {
    api.pluginAppliedProjects.mockResolvedValue([
      { projectId: 7, projectName: '门店 POS 项目', appliedAt: '2026-09-21 10:00:00' },
    ])
    const { w } = factory({ modelValue: true, plugin: plugin() })
    await vi.waitFor(() => expect(api.pluginDetail).toHaveBeenCalledWith(41))
    expect(api.pluginAppliedProjects).toHaveBeenCalledWith(41)
    const html = w.html()
    expect(html).toContain('pos-pack')
    expect(html).toContain('收银规范')
    expect(html).toContain('门店 POS 项目')
    expect(w.find('[data-test="plugin-detail-revision"]').text()).toContain('3')
  })

  it('内置样例（source=1）=编辑入口隐藏（只读语义镜像 core 40301）', async () => {
    api.pluginDetail.mockResolvedValue(plugin({ id: 1, name: 'pos-retail', source: 1 }))
    const { w } = factory({ modelValue: true, plugin: plugin({ id: 1, name: 'pos-retail', source: 1 }) })
    await vi.waitFor(() => expect(w.find('[data-test="plugin-detail-revision"]').exists()).toBe(true))
    expect(w.find('[data-test="plugin-edit-open"]').exists()).toBe(false)
  })
})

describe('T242 P3 PluginDetailDrawer 编辑态', () => {
  it('点编辑 → 表单预填 manifest；改版本保存 → PUT 携 revision+JSON 描述符；成功 emit saved', async () => {
    const { w } = factory({ modelValue: true, plugin: plugin() })
    await vi.waitFor(() => expect(w.find('[data-test="plugin-edit-open"]').exists()).toBe(true))
    await w.find('[data-test="plugin-edit-open"]').trigger('click')
    const versionInput = w.find('input[data-test="plugin-field-version"]')
    await vi.waitFor(() => expect(versionInput.exists()).toBe(true))
    expect(versionInput.element.value).toBe('1.0.0')
    await versionInput.setValue('1.0.1')
    await w.find('[data-test="plugin-edit-submit"]').trigger('click')
    await vi.waitFor(() => expect(api.pluginUpdate).toHaveBeenCalled())
    const [id, revision, content] = api.pluginUpdate.mock.calls[0]
    expect(id).toBe(41)
    expect(revision).toBe(3)
    const manifest = JSON.parse(content)
    expect(manifest.version).toBe('1.0.1')
    expect(manifest.name).toBe('pos-retail-custom')
    await vi.waitFor(() => expect(w.emitted('saved')).toBeTruthy())
  })

  it('必填缺失提交 → 行内错误显示且禁发 PUT（TC-V15-01 非法不落脏浏览器面）', async () => {
    const { w } = factory({ modelValue: true, plugin: plugin() })
    await vi.waitFor(() => expect(w.find('[data-test="plugin-edit-open"]').exists()).toBe(true))
    await w.find('[data-test="plugin-edit-open"]').trigger('click')
    const nameInput = w.find('input[data-test="plugin-field-name"]')
    await vi.waitFor(() => expect(nameInput.exists()).toBe(true))
    await nameInput.setValue('')
    await w.find('[data-test="plugin-edit-submit"]').trigger('click')
    await vi.waitFor(() =>
      expect(w.find('[data-test="plugin-edit-errors"]').text()).toContain('name'))
    expect(api.pluginUpdate).not.toHaveBeenCalled()
  })

  it('40901 版本冲突 → 后端 msg 人话 toast 直出，禁 emit saved', async () => {
    api.pluginUpdate.mockRejectedValue(
      new BizError('40901', '插件已被他人修改，请刷新后重试（当前版本 5）'))
    const { w } = factory({ modelValue: true, plugin: plugin() })
    await vi.waitFor(() => expect(w.find('[data-test="plugin-edit-open"]').exists()).toBe(true))
    await w.find('[data-test="plugin-edit-open"]').trigger('click')
    await w.find('[data-test="plugin-edit-submit"]').trigger('click')
    await vi.waitFor(() => expect(document.body.textContent).toContain('插件已被他人修改'))
    expect(w.emitted('saved')).toBeFalsy()
  })
})

describe('T242 P3 接线与 i18n 镜像闸', () => {
  it('PluginListView 消费抽屉（查看/编辑入口接线，防孤儿件）', async () => {
    const listViewRaw = (await import('@/views/plugin/PluginListView.vue?raw')).default
    expect(listViewRaw).toContain('PluginDetailDrawer')
    expect(listViewRaw).toContain('plugin-view-open')
  })

  it('抽屉 t(\'plugin.*\') 键在 zh-CN/en-US 双写全命中（T239 镜像律）', () => {
    const keys = [...drawerRaw.matchAll(/t\('(plugin\.[A-Za-z0-9_.]+)'/g)].map((m) => m[1])
    expect(keys.length).toBeGreaterThanOrEqual(10)
    const zh = zhCN as Record<string, any>
    const en = enUS as Record<string, any>
    for (const k of new Set(keys)) {
      const get = (loc: Record<string, any>) => k.split('.').reduce((o, p) => (o == null ? o : o[p]), loc)
      expect(get(zh), `zh-CN 缺键 ${k}`).toBeTruthy()
      expect(get(en), `en-US 缺键 ${k}`).toBeTruthy()
    }
  })
})

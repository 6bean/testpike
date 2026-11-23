
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  listHelpDocs: vi.fn(),
  getHelpDomainDocs: vi.fn(),
}))
vi.mock('@/api/help', () => api)

import { closeHelp, helpDrawerState, openHelp } from '@/composables/useHelpDrawer'
import HelpDrawer from '@/components/HelpDrawer.vue'
import helpApiRaw from '@/api/help?raw'
import mainLayoutRaw from '@/layouts/MainLayout.vue?raw'
import drawerRaw from '@/components/HelpDrawer.vue?raw'


function doc(over: Record<string, unknown> = {}) {
  return {
    id: 1, domainCode: 'A', title: '用例生成快速上手', bodyMd: null,
    sortNo: 1, version: 'v2.40', updatedAt: '2026-09-21 12:00:00', ...over,
  }
}

function factory() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const w = mount(HelpDrawer, { global: { plugins: [ElementPlus, i18n, pinia] } })
  mounted.push(w)
  return { w, i18n }
}


const mounted: { unmount: () => void }[] = []

beforeEach(() => {
  vi.clearAllMocks()
  closeHelp()
  api.listHelpDocs.mockResolvedValue([])
  api.getHelpDomainDocs.mockResolvedValue([])
})

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

describe('T241 P3 api/help.ts 契约镜像闸', () => {
  it('两端点 URL/method 与 admin HelpController 逐一对齐（/help/docs 索引 + /help/docs/{domain} 详情，全 GET）', () => {
    expect(helpApiRaw).toContain("url: '/help/docs'")
    expect(helpApiRaw).toContain('method: \'get\'')
    expect(helpApiRaw).toMatch(/url: *`\/help\/docs\/\$\{[^}]+\}`/)
    expect(helpApiRaw).not.toContain("method: 'post'")
    expect(helpApiRaw).not.toContain("method: 'put'")
  })
})

describe('T241 P3 useHelpDrawer 单件状态', () => {
  it('openHelp() 开抽屉不带域；openHelp("G") 带锚跳域；closeHelp() 收（模块级单例，MainLayout/组件锚跳共用同一状态）', async () => {
    expect(helpDrawerState.visible).toBe(false)
    openHelp()
    expect(helpDrawerState.visible).toBe(true)
    expect(helpDrawerState.domain).toBe('')
    openHelp('G')
    expect(helpDrawerState.domain).toBe('G')
    closeHelp()
    expect(helpDrawerState.visible).toBe(false)
  })
})

describe('T241 P3 HelpDrawer 组件态', () => {
  it('visible 打开即拉索引；侧栏按域码分组渲染章节标题（sort 升序，域内索引源=列表态无正文）', async () => {
    api.listHelpDocs.mockResolvedValue([
      doc({ id: 1, domainCode: 'A', title: 'A2 生成', sortNo: 1 }),
      doc({ id: 2, domainCode: 'A', title: 'A5 导入', sortNo: 2 }),
      doc({ id: 3, domainCode: 'G', title: 'G1 文档入库', sortNo: 1 }),
    ])
    openHelp()
    const { w } = factory()
    await vi.waitFor(() => expect(api.listHelpDocs).toHaveBeenCalledTimes(1))
    const items = w.findAll('[data-test="help-doc-item"]')
    expect(items).toHaveLength(3)
    expect(items.map((x) => x.text()).join('|')).toContain('G1 文档入库')
    const heads = w.findAll('[data-test="help-domain-group"]').map((x) => x.text())
    expect(heads).toHaveLength(2)
    expect(heads[0]).toBe('写用例：AI 生成')
    expect(heads[1]).toBe('知识库')
    expect(api.getHelpDomainDocs).not.toHaveBeenCalled()
  })

  it('点选章节→拉该域详情+MarkdownPreview 渲染正文；同域二次点选走缓存禁重复请求（红线⑥复用闸）', async () => {
    api.listHelpDocs.mockResolvedValue([doc({ domainCode: 'A', title: 'A2 生成' })])
    api.getHelpDomainDocs.mockResolvedValue([
      doc({ bodyMd: '## SKILL 调用逻辑\n正文内容', title: 'A2 生成' }),
    ])
    openHelp()
    const { w } = factory()
    await vi.waitFor(() => expect(api.listHelpDocs).toHaveBeenCalledTimes(1))
    await w.find('[data-test="help-doc-item"]').trigger('click')
    await vi.waitFor(() => expect(api.getHelpDomainDocs).toHaveBeenCalledWith('A'))
    expect(w.find('[data-test="markdown-preview"]').exists()).toBe(true)
    expect(w.html()).toContain('SKILL 调用逻辑')
    await w.find('[data-test="help-doc-item"]').trigger('click')
    await vi.waitFor(() => expect(api.getHelpDomainDocs).toHaveBeenCalledTimes(1))
  })

  it('openHelp("G") 锚跳语义：索引就位后自动选中域 G 首章并拉详情（?domain=X 消费面）', async () => {
    api.listHelpDocs.mockResolvedValue([
      doc({ id: 1, domainCode: 'A', title: 'A2 生成' }),
      doc({ id: 2, domainCode: 'G', title: 'G1 文档入库', sortNo: 1 }),
    ])
    api.getHelpDomainDocs.mockResolvedValue([
      doc({ id: 2, domainCode: 'G', title: 'G1 文档入库', bodyMd: 'G 正文' }),
    ])
    openHelp('G')
    const { w } = factory()
    await vi.waitFor(() => expect(api.getHelpDomainDocs).toHaveBeenCalledWith('G'))
    expect(w.html()).toContain('G 正文')
  })

  it('搜索框直 fill 过滤章节标题（TC-V12-01 前端面：输入即过滤，清空恢复全索引）', async () => {
    api.listHelpDocs.mockResolvedValue([
      doc({ id: 1, domainCode: 'A', title: '用例生成' }),
      doc({ id: 2, domainCode: 'K', title: 'SKILL 调用逻辑' }),
    ])
    openHelp()
    const { w } = factory()
    await vi.waitFor(() => expect(api.listHelpDocs).toHaveBeenCalledTimes(1))
    const search = w.find('[data-test="help-search"] input')
    await search.setValue('SKILL')
    expect(w.findAll('[data-test="help-doc-item"]')).toHaveLength(1)
    expect(w.find('[data-test="help-doc-item"]').text()).toContain('SKILL 调用逻辑')
    await search.setValue('')
    expect(w.findAll('[data-test="help-doc-item"]')).toHaveLength(2)
  })

  it('T389 锚跳带章标题关键词：域内命中专章选中（升级中心不再跳 K 域首章「管理员通览」）', async () => {
    api.listHelpDocs.mockResolvedValue([
      doc({ id: 21, domainCode: 'K', title: '管理员：权限、配置与平台保障', sortNo: 1 }),
      doc({ id: 22, domainCode: 'K', title: '升级中心：发版前后这里看什么', sortNo: 2 }),
    ])
    api.getHelpDomainDocs.mockResolvedValue([
      doc({ id: 21, domainCode: 'K', title: '管理员：权限、配置与平台保障', bodyMd: '通览正文' }),
      doc({ id: 22, domainCode: 'K', title: '升级中心：发版前后这里看什么', bodyMd: '升级中心专章正文' }),
    ])
    openHelp('K', '升级中心')
    const { w } = factory()
    await vi.waitFor(() => expect(api.getHelpDomainDocs).toHaveBeenCalledWith('K'))
    expect(w.html()).toContain('升级中心专章正文')
    expect(w.html()).not.toContain('通览正文')
  })

  it('T389 锚关键词未命中=回落域首章（现行为保底，不空正文不报错）', async () => {
    api.listHelpDocs.mockResolvedValue([
      doc({ id: 21, domainCode: 'K', title: '管理员：权限、配置与平台保障', sortNo: 1 }),
      doc({ id: 22, domainCode: 'K', title: '升级中心：发版前后这里看什么', sortNo: 2 }),
    ])
    api.getHelpDomainDocs.mockResolvedValue([
      doc({ id: 21, domainCode: 'K', title: '管理员：权限、配置与平台保障', bodyMd: '通览正文' }),
      doc({ id: 22, domainCode: 'K', title: '升级中心：发版前后这里看什么', bodyMd: '升级中心专章正文' }),
    ])
    openHelp('K', '不存在的章节锚')
    const { w } = factory()
    await vi.waitFor(() => expect(api.getHelpDomainDocs).toHaveBeenCalledWith('K'))
    expect(w.html()).toContain('通览正文')
  })

  it('空索引=空态人话（tp_help_doc 0 行现况不白屏不报错）', async () => {
    openHelp()
    const { w } = factory()
    await vi.waitFor(() => expect(api.listHelpDocs).toHaveBeenCalledTimes(1))
    expect(w.find('[data-test="help-empty"]').exists()).toBe(true)
  })
})

describe('T241 P3 接线与 i18n 镜像闸', () => {
  it('MainLayout renders one header help dot (data-test=header-help-btn) wired to HelpDrawer + useHelpDrawer singleton (orphan guard, R23 stage 1)', () => {
    expect(mainLayoutRaw).toContain('data-test="header-help-btn"')
    // Anchor migration (same batch, R24 rule): the generic help-trigger anchor
    // is retired because the button moved next to the header title.
    expect(mainLayoutRaw).not.toContain('data-test="help-trigger"')
    expect(mainLayoutRaw).toContain('HelpDrawer')
    expect(mainLayoutRaw).toContain('useHelpDrawer')
  })

  it('the topbar renders exactly one QuestionFilled entry (single ? point ruling)', () => {
    const header = mainLayoutRaw.slice(
      mainLayoutRaw.indexOf('<el-header'),
      mainLayoutRaw.indexOf('</el-header>'),
    )
    expect((header.match(/<QuestionFilled \/>/g) ?? []).length).toBe(1)
  })

  it('HelpDrawer.vue 的 t(\'help.*\') 键在 zh-CN/en-US 双写全命中（T239 镜像律）', () => {
    const keys = [...drawerRaw.matchAll(/t\('(help\.[A-Za-z0-9_.]+)'/g)].map((m) => m[1])
    expect(keys.length).toBeGreaterThanOrEqual(5)
    const zh = zhCN as Record<string, any>
    const en = enUS as Record<string, any>
    for (const k of new Set(keys)) {
      const get = (loc: Record<string, any>) => k.split('.').reduce((o, p) => (o == null ? o : o[p]), loc)
      expect(get(zh), `zh-CN 缺键 ${k}`).toBeTruthy()
      expect(get(en), `en-US 缺键 ${k}`).toBeTruthy()
    }
  })
})

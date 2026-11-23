
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  listAppautoEngines: vi.fn(),
  precheckPortability: vi.fn(),
  rerunBatchCase: vi.fn(),
}))
vi.mock('@/api/appauto', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  listAppautoEngines: api.listAppautoEngines,
  precheckPortability: api.precheckPortability,
}))
vi.mock('@/api/executor', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  rerunBatchCase: api.rerunBatchCase,
}))

import AppautoEngineRerunDialog from '@/components/AppautoEngineRerunDialog.vue'
import appautoRaw from '@/api/appauto?raw'
import executorRaw from '@/api/executor?raw'

function engineOf(code: string, name: string) {
  return { id: 1, code, name, platformCodes: ['ANDROID'], deviceKindCodes: ['PHONE'], defaultPriority: 1, status: 1 }
}

function portabilityOf(verdict: string, items: string[] = []) {
  return {
    engineCode: 'MAESTRO', engineName: 'Maestro', caseId: 2, caseTitle: '登录用例',
    verdict, items, stepCount: 3,
  }
}

const mounted: { unmount: () => void }[] = []

async function factory() {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const w = mount(AppautoEngineRerunDialog, {
    props: {
      modelValue: false, projectId: 1, caseId: 2, taskId: 77, sourceEngineCode: 'MOBILEWRIGHT',
    },
    global: { plugins: [ElementPlus, i18n] },
  })
  mounted.push(w)
  w.setProps({ modelValue: true })
  await flushPromises()
  return w
}

beforeEach(() => {
  api.listAppautoEngines.mockReset().mockResolvedValue([
    engineOf('MOBILEWRIGHT', 'Mobilewright'), engineOf('MAESTRO', 'Maestro'),
  ])
  api.precheckPortability.mockReset().mockResolvedValue(portabilityOf('RUNNABLE'))
  api.rerunBatchCase.mockReset().mockResolvedValue({ taskId: 78, created: true, message: '已重派' })
})

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

describe('api 契约镜像闸', () => {
  it('appauto.ts 镜像 portability 口（GET /appauto/portability，七字段视图）', () => {
    expect(appautoRaw).toContain("'/appauto/portability'")
    expect(appautoRaw).toMatch(/precheckPortability[\s\S]{0,400}method: 'get'/)
    expect(appautoRaw).toMatch(/interface AppautoPortability \{[\s\S]*?engineCode: string[\s\S]*?verdict: string[\s\S]*?items:[\s\S]*?stepCount/)
  })
  it('executor.ts CaseExecHistoryItem 补 engineCode 信封投影列（web=null 诚实缺位）', () => {
    expect(executorRaw).toMatch(/interface CaseExecHistoryItem \{[\s\S]*?engineCode\?: string \| null/)
  })
})

describe('AppautoEngineRerunDialog 预检-派发流程', () => {
  it('打开即拉启用引擎面；源引擎剔除；未选目标引擎时预检与派发均禁用', async () => {
    const w = await factory()
    expect(api.listAppautoEngines).toHaveBeenCalled()
    expect(w.find('[data-test="rerun-engine-opt-MOBILEWRIGHT"]').exists()).toBe(false)
    expect(w.find('[data-test="rerun-engine-opt-MAESTRO"]').exists()).toBe(true)
    expect(w.find('[data-test="rerun-precheck-btn"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="rerun-dispatch-btn"]').attributes('disabled')).toBeDefined()
    expect(api.precheckPortability).not.toHaveBeenCalled()
  })

  it('RUNNABLE=绿灯判定+放行派发；派发带 engineCode 调 O9 重跑并 emit done', async () => {
    const w = await factory()
    await w.find('[data-test="rerun-engine-opt-MAESTRO"]').trigger('click')
    await w.find('[data-test="rerun-precheck-btn"]').trigger('click')
    await flushPromises()
    expect(api.precheckPortability).toHaveBeenCalledWith(1, 2, 'MAESTRO')
    expect(w.find('[data-test="rerun-verdict"]').text()).toContain('可直接迁移')
    await w.find('[data-test="rerun-dispatch-btn"]').trigger('click')
    await flushPromises()
    expect(api.rerunBatchCase).toHaveBeenCalledWith(77, 'MAESTRO')
    expect(w.emitted('done')?.[0]).toEqual([{ taskId: 78, created: true, message: '已重派' }])
  })

  it('DEGRADED=黄灯+items 逐条人话展开（降级点不吞），仍放行派发', async () => {
    api.precheckPortability.mockResolvedValue(portabilityOf('DEGRADED', ['滑动操作将降级为坐标拖拽']))
    const w = await factory()
    await w.find('[data-test="rerun-engine-opt-MAESTRO"]').trigger('click')
    await w.find('[data-test="rerun-precheck-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="rerun-verdict"]').text()).toContain('降级可跑')
    expect(w.find('[data-test="rerun-items"]').text()).toContain('滑动操作将降级为坐标拖拽')
    expect(w.find('[data-test="rerun-dispatch-btn"]').attributes('disabled')).toBeUndefined()
  })

  it('REWRITE=红灯+改写清单逐条+派发禁用（拒派回=UX 拦截，服务端差集闸为权威二道）', async () => {
    api.precheckPortability.mockResolvedValue(portabilityOf('REWRITE', ['app.swipe 目标引擎不支持', '缺少图像定位能力']))
    const w = await factory()
    await w.find('[data-test="rerun-engine-opt-MAESTRO"]').trigger('click')
    await w.find('[data-test="rerun-precheck-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="rerun-verdict"]').text()).toContain('需改写')
    const items = w.find('[data-test="rerun-items"]').text()
    expect(items).toContain('app.swipe 目标引擎不支持')
    expect(items).toContain('缺少图像定位能力')
    expect(w.find('[data-test="rerun-dispatch-btn"]').attributes('disabled')).toBeDefined()
  })

  it('换选引擎后旧预检结果作废（禁拿上一个引擎的绿灯派发到新引擎）', async () => {
    api.precheckPortability.mockResolvedValue(portabilityOf('RUNNABLE'))
    const w = await factory()
    await w.find('[data-test="rerun-engine-opt-MAESTRO"]').trigger('click')
    await w.find('[data-test="rerun-precheck-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="rerun-verdict"]').exists()).toBe(true)
    await w.find('[data-test="rerun-engine-opt-MAESTRO"]').trigger('click')
    expect(w.find('[data-test="rerun-verdict"]').exists()).toBe(false)
    expect(w.find('[data-test="rerun-dispatch-btn"]').attributes('disabled')).toBeDefined()
  })

  it('派发被服务端拒（REWRITE 二道闸人话）→ 错误原文直透展示禁吞，不 emit done', async () => {
    api.rerunBatchCase.mockRejectedValue({ message: '该用例含目标引擎不支持的步骤，需先改写' } as never)
    const w = await factory()
    await w.find('[data-test="rerun-engine-opt-MAESTRO"]').trigger('click')
    await w.find('[data-test="rerun-precheck-btn"]').trigger('click')
    await flushPromises()
    await w.find('[data-test="rerun-dispatch-btn"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="rerun-dispatch-error"]').text()).toContain('该用例含目标引擎不支持的步骤')
    expect(w.emitted('done')).toBeUndefined()
  })
})

describe('i18n 双轨镜像闸', () => {
  const usedKeys = [
    'title', 'engineCol', 'precheckBtn', 'dispatchBtn', 'cancelBtn',
    'verdictRunnable', 'verdictDegraded', 'verdictRewrite', 'itemsTitle', 'rewriteHint',
  ]
  for (const k of usedKeys) {
    it(`appauto.rerun.${k} zh-CN/en-US 双写命中`, () => {
      const zh = (zhCN as Record<string, any>).appauto?.rerun?.[k]
      const en = (enUS as Record<string, any>).appauto?.rerun?.[k]
      expect(zh, `zh-CN 缺 appauto.rerun.${k}`).toBeTruthy()
      expect(en, `en-US 缺 appauto.rerun.${k}`).toBeTruthy()
    })
  }
})

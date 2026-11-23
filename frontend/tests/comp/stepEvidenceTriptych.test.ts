
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'

const api = vi.hoisted(() => ({
  listStepEvidence: vi.fn(),
}))
vi.mock('@/api/executor', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/executor')>()
  return { ...actual, listStepEvidence: api.listStepEvidence }
})

import ExecStepEvidencePanel from '@/components/ExecStepEvidencePanel.vue'

function taskOf(timeline: unknown[], over: Record<string, unknown> = {}) {
  return {
    id: 7, projectId: 1, caseId: 2, status: 4,
    stepsTimeline: JSON.stringify(timeline), ...over,
  }
}

function evRow(stepNo: number, over: Record<string, unknown> = {}) {
  return {
    taskId: 7, stepNo, action: 'app.tap',
    screenshotKey: `p/${stepNo}.png`, screenshotUrl: `http://s3obj/s${stepNo}?sig=1`,
    viewTreeKey: `p/${stepNo}.json`, viewTreeUrl: `http://s3obj/t${stepNo}?sig=1`,
    logTailKey: null, logTailUrl: null,
    recordKey: null, recordUrl: null,
    netTimelineKey: null, netTimelineUrl: null, ...over,
  }
}

async function factory(task: unknown) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  const w = mount(ExecStepEvidencePanel, {
    props: { task },
    global: { plugins: [ElementPlus, i18n] },
  })
  await flushPromises()
  return w
}

beforeEach(() => {
  api.listStepEvidence.mockReset().mockResolvedValue([])
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true, status: 200, text: () => Promise.resolve('{"nodes":[]}'),
  }))
})

describe('ExecStepEvidencePanel 加载策略', () => {
  it('全成功时间线=零外呼（无失败步不读证据面，存量零扰）', async () => {
    await factory(taskOf([{ stepNo: 1, status: 'ok' }]))
    expect(api.listStepEvidence).not.toHaveBeenCalled()
  })
  it('含失败步=按任务 id 拉一次 step-evidence', async () => {
    await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    expect(api.listStepEvidence).toHaveBeenCalledTimes(1)
    expect(api.listStepEvidence).toHaveBeenCalledWith(7)
  })
  it('读面空=整区隐藏（存量非 APP 任务无 evidence 键零漂移）', async () => {
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    expect(w.find('[data-test="exec-triptych"]').exists()).toBe(false)
  })
})

describe('ExecStepEvidencePanel 三联图渲染', () => {
  it('失败步四槽：截图在位/视图树在位/日志尾缺位=诚实占位人话', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1)])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail', action: 'app.tap' }]))
    expect(w.find('[data-test="exec-triptych"]').exists()).toBe(true)
    expect(w.find('[data-test="triptych-pane-screenshot"]').exists()).toBe(true)
    const treePane = w.get('[data-test="triptych-pane-viewTree"]')
    expect(treePane.text()).toContain('视图树')
    const logPane = w.get('[data-test="triptych-pane-logTail"]')
    expect(logPane.classes()).toContain('pane-absent')
    expect(logPane.text()).toContain('缺位')
  })
  it('T327 第四联：record 在位=渲染 video 播放位（预签名 src）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1, { recordKey: 'p/1.mp4', recordUrl: 'http://s3obj/r1?sig=1' })])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    const recPane = w.get('[data-test="triptych-pane-record"]')
    expect(recPane.text()).toContain('录屏切片')
    const video = w.get('[data-test="triptych-record-video"]')
    expect(video.attributes('src')).toBe('http://s3obj/r1?sig=1')
  })
  it('T327 第四联：record 缺位=诚实占位（编码环境件缺位不假造回放，禁空 video）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1)])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    const recPane = w.get('[data-test="triptych-pane-record"]')
    expect(recPane.classes()).toContain('pane-absent')
    expect(w.find('[data-test="triptych-record-video"]').exists()).toBe(false)
  })
  it('T328 第五联：netTimeline 在位=文本件展开现拉（弱网观测时序 JSONL 同径复用文本分支）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1, {
      netTimelineKey: 'p/1.jsonl', netTimelineUrl: 'http://s3obj/n1?sig=1',
    })])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    const netPane = w.get('[data-test="triptych-pane-netTimeline"]')
    expect(netPane.text()).toContain('弱网观测时序')
    expect(netPane.classes()).not.toContain('pane-absent')
    await w.get('[data-test="triptych-text-toggle-netTimeline"]').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledWith('http://s3obj/n1?sig=1')
    expect(w.get('[data-test="triptych-pane-text"]').text()).toContain('"nodes"')
  })
  it('T328 第五联：netTimeline 缺位=诚实占位（无弱网会话不假造时序，存量行零漂移）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1)])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    const netPane = w.get('[data-test="triptych-pane-netTimeline"]')
    expect(netPane.classes()).toContain('pane-absent')
    expect(netPane.text()).toContain('弱网观测时序缺位')
  })
  it('T329 第六联：perfTimeline 在位=「查看曲线」钮+展开经预签名 fetch 拉 JSONL 直绘面板（stub 直绘件隔离 echarts）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1, {
      perfTimelineKey: 'p/1-perf.jsonl', perfTimelineUrl: 'http://s3obj/pf1?sig=1',
    })])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true, status: 200,
      text: () => Promise.resolve('{"ts":100.0,"jankPct":12.5,"cpuPct":40,"tempC":38.2,"pssKb":102400}\nnot-a-json\n{"ts":101.0}\n'),
    }))
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    const perfPane = w.get('[data-test="triptych-pane-perfTimeline"]')
    expect(perfPane.text()).toContain('性能采样曲线')
    expect(perfPane.classes()).not.toContain('pane-absent')
    expect(fetch).not.toHaveBeenCalled()
    await w.get('[data-test="triptych-perf-toggle"]').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledWith('http://s3obj/pf1?sig=1')
    const chart = w.findComponent({ name: 'PerfTimelinePane' })
    expect(chart.exists()).toBe(true)
    expect(chart.props('samples')).toHaveLength(2)
    expect((chart.props('samples') as { ts: number }[])[1].ts).toBe(101)
  })
  it('T329 第六联：perfTimeline 缺位=诚实占位（无性能会话不假造曲线，存量行零漂移）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1)])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    const perfPane = w.get('[data-test="triptych-pane-perfTimeline"]')
    expect(perfPane.classes()).toContain('pane-absent')
    expect(perfPane.text()).toContain('性能采样曲线缺位')
    expect(w.find('[data-test="triptych-perf-toggle"]').exists()).toBe(false)
  })
  it('T329 第六联：曲线件 fetch 失败=空样行诚实态（面板挂载空清单，禁空白假装有内容）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1, {
      perfTimelineKey: 'p/1-perf.jsonl', perfTimelineUrl: 'http://s3obj/pf1?sig=403',
    })])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403, text: () => Promise.resolve('') }))
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    await w.get('[data-test="triptych-perf-toggle"]').trigger('click')
    await flushPromises()
    const chart = w.findComponent({ name: 'PerfTimelinePane' })
    expect(chart.exists()).toBe(true)
    expect(chart.props('samples')).toHaveLength(0)
  })
  it('视图树「查看全文」=展开后经预签名 URL fetch 拉文本', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(1)])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    await w.get('[data-test="triptych-text-toggle-viewTree"]').trigger('click')
    await flushPromises()
    expect(fetch).toHaveBeenCalledWith('http://s3obj/t1?sig=1')
    expect(w.get('[data-test="triptych-pane-text"]').text()).toContain('"nodes"')
  })
  it('fetch 失败=过期人话提示（禁空白假装有内容）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403, text: () => Promise.resolve('') }))
    api.listStepEvidence.mockResolvedValue([evRow(1)])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    await w.get('[data-test="triptych-text-toggle-viewTree"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-test="triptych-text-error"]').text()).toContain('过期')
  })
  it('证据行存在但该失败步无对应行=「无现场证据」诚实态（并表由纯逻辑件保证）', async () => {
    api.listStepEvidence.mockResolvedValue([evRow(9)])
    const w = await factory(taskOf([{ stepNo: 1, status: 'fail' }]))
    expect(w.find('[data-test="triptych-none"]').exists()).toBe(true)
  })
})

describe('api 契约镜像闸（listStepEvidence ↔ admin ExecutorController）', () => {
  it('executor.ts 源码含 /executors/tasks/{id}/step-evidence GET 且返回数组', () => {
    const raw = import.meta.glob('../../src/api/executor.ts', { query: '?raw', import: 'default', eager: true })
    const src = Object.values(raw)[0] as string
    expect(src).toMatch(
      /listStepEvidence\(taskId: number\)[\s\S]{0,120}url: `\/executors\/tasks\/\$\{taskId\}\/step-evidence`, method: 'get'/,
    )
  })
})

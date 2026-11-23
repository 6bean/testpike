/**
 * the tail drawer consumes the two gated read doors over one
 * exchange trail : the masked line gets an audited
 * "show plaintext" reveal that lands only in memory, the honest oversized
 * body marker gets a paged full-text read (cursor = from + text.length),
 * detail becomes a collapsible block (live newest row open by default,
 * history collapsed), and the copy door stays masked-only. Hosts without a
 * wired door (extraction / generation tails) keep the legacy presentation
 * with zero drift — the buttons are an honest absence there.
 * Case names stay Chinese for suite-wide readability parity with the
 * sibling taskTailWindowing.test.ts spectra.
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'

const request = vi.fn()
vi.mock('@/api/request', () => ({
  getToken: () => 'test-token',
  request: (...args: unknown[]) => request(...args),
  // Mirror every export the tail module graph names at import time (see
  // taskTailDrawer spec; pre-existing red cleared 11-22).
  ensureFreshAccessToken: vi.fn(async () => 'test-token'),
  renewAccessTokenOnce: vi.fn(async () => false),
}))

vi.mock('@/api/config', () => ({
  listConfigs: vi.fn(async () => []),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPerm: () => true }),
}))

import { credentialTrailDoors, probeTrailDoors } from '@/api/taskEvents'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'

/** One masked request trail as the server renders it (ProbeExchangeRunner). */
const REQUEST_TRAIL = [
  '请求行：POST https://api.example.com/login',
  '请求头（共 3 项）：',
  'content-type: application/json',
  'authorization: Bearer a1****（总长度 42）',
  'cookie: sid=***（总长度 24）; theme=***（总长度 4）',
].join('\n')

/** One over-budget response trail: pointer line + full twin behind the door. */
const RESPONSE_TRAIL = [
  'HTTP 状态：200 · 34ms',
  '响应头（共 1 项）：',
  'set-cookie: session=***（总长度 60）',
  '响应体（字节预算 65536）：',
  '{"ok":true',
  '（响应共 70000 字节，超出预算 4464 字节，可点「展开全文」读取完整响应）',
].join('\n')

function row(cursor: number, detail: string | null, summary = `step ${cursor}`) {
  return { cursor, level: 'step', summary, detail, landedAt: '2026-11-19T10:00:00' }
}

function mountDrawer(props: Record<string, unknown>) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(TaskTailDrawer, {
    props: { events: [], live: false, ...props } as never,
    global: { plugins: [i18n, ElementPlus] },
  })
}

function doorsFixture() {
  const reveal = vi.fn(async () => ({ field: 'request:authorization', values: ['Bearer super-secret-value'] }))
  const fullText = vi.fn(async () => ({ text: '"}]', from: 0, totalChars: 70000, hasMore: false }))
  return { reveal, fullText, api: { reveal, fullText } }
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('trail door clients (T663 slice 4)', () => {
  it('probeTrailDoors 走登录态探测门的 reveal/full 两端点', async () => {
    request.mockResolvedValue({ field: 'x', values: [] })
    await probeTrailDoors(7).reveal(99, 'request:authorization')
    expect(request).toHaveBeenCalledWith({
      url: '/session-states/7/probe-events/99/reveal',
      method: 'get',
      params: { field: 'request:authorization' },
    })
    await probeTrailDoors(7).fullText(99, 120)
    expect(request).toHaveBeenCalledWith({
      url: '/session-states/7/probe-events/99/full',
      method: 'get',
      params: { from: 120 },
    })
  })

  it('credentialTrailDoors 走凭据验证门的同名双端点', async () => {
    request.mockResolvedValue({ field: 'x', values: [] })
    await credentialTrailDoors(5).reveal(3, 'response:set-cookie')
    expect(request).toHaveBeenCalledWith({
      url: '/appauto/credentials/5/test-events/3/reveal',
      method: 'get',
      params: { field: 'response:set-cookie' },
    })
  })
})

describe('TaskTailDrawer 展开块与缺省展开 (T663 slice 4)', () => {
  it('接门宿主进行中最新行缺省展开、历史行缺省收起，收起行可点展开', async () => {
    const { api } = doorsFixture()
    const wrapper = mountDrawer({
      live: true, doors: api,
      events: [row(1, REQUEST_TRAIL), row(2, RESPONSE_TRAIL)],
    })
    await wrapper.vm.$nextTick()
    // newest live row (cursor 2) is open by default, history row (cursor 1) is not
    expect(wrapper.find('[data-test="trail-detail-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="trail-detail-1"]').exists()).toBe(false)
    // toggle history open then back to collapsed — user choice wins
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    expect(wrapper.find('[data-test="trail-detail-1"]').exists()).toBe(true)
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    expect(wrapper.find('[data-test="trail-detail-1"]').exists()).toBe(false)
    // collapsed rows keep the legacy single-line detail presentation
    expect(wrapper.find('.task-tail-detail').exists()).toBe(true)
  })

  it('未接门宿主（提取/生成尾窗）live 最新行也不自动展开，旧形态零漂移', async () => {
    const wrapper = mountDrawer({
      live: true,
      events: [row(1, REQUEST_TRAIL)],
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-test="trail-detail-1"]').exists()).toBe(false)
    expect(wrapper.find('.task-tail-detail').exists()).toBe(true)
    // the expand toggle still exists — reading a big trail is one click away
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    expect(wrapper.find('[data-test="trail-detail-1"]').exists()).toBe(true)
  })
})

describe('TaskTailDrawer 掩码行解显 (T663 slice 4)', () => {
  it('掩码头行带显示明文钮，点后按行内出现序渲染明文进内存态', async () => {
    const { api } = doorsFixture()
    const wrapper = mountDrawer({ live: false, events: [row(1, REQUEST_TRAIL)], doors: api })
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    const revealBtn = wrapper.find('[data-test="trail-reveal-request:authorization"]')
    expect(revealBtn.exists()).toBe(true)
    await revealBtn.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(api.reveal).toHaveBeenCalledWith(1, 'request:authorization')
    const plain = wrapper.find('[data-test="trail-revealed-request:authorization"]')
    expect(plain.exists()).toBe(true)
    expect(plain.text()).toContain('Bearer super-secret-value')
  })

  it('cookie 成对掩码行同样可解显，字段键=request:cookie', async () => {
    const { api } = doorsFixture()
    const wrapper = mountDrawer({ live: false, events: [row(1, REQUEST_TRAIL)], doors: api })
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    expect(wrapper.find('[data-test="trail-reveal-request:cookie"]').exists()).toBe(true)
  })

  it('非敏感头行与正文行不出现解显钮', async () => {
    const { api } = doorsFixture()
    const wrapper = mountDrawer({ live: false, events: [row(1, REQUEST_TRAIL)], doors: api })
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    expect(wrapper.find('[data-test="trail-reveal-request:content-type"]').exists()).toBe(false)
  })

  it('未接门的宿主（提取/生成尾窗）解显钮诚实缺席，零漂移', async () => {
    const wrapper = mountDrawer({ live: false, events: [row(1, REQUEST_TRAIL)] })
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    expect(wrapper.find('[data-test^="trail-reveal-"]').exists()).toBe(false)
  })

  it('解显失败点名服务端原因，明文不出现', async () => {
    const err = Object.assign(new Error('字段「request:authorization」当时未能密文留存'), {})
    const api = { reveal: vi.fn(async () => { throw err }), fullText: vi.fn() }
    const spy = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)
    const wrapper = mountDrawer({ live: false, events: [row(1, REQUEST_TRAIL)], doors: api })
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    await wrapper.find('[data-test="trail-reveal-request:authorization"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(spy).toHaveBeenCalledWith(expect.stringContaining('密文留存'))
    expect(wrapper.find('[data-test="trail-revealed-request:authorization"]').exists()).toBe(false)
    spy.mockRestore()
  })
})

describe('TaskTailDrawer 展开全文游标续页 (T663 slice 4)', () => {
  it('超预算标记行给展开全文钮；hasMore 时续页游标=from+已读长度，读完收钮', async () => {
    const fullText = vi.fn()
      .mockResolvedValueOnce({ text: 'AAA', from: 0, totalChars: 9, hasMore: true })
      .mockResolvedValueOnce({ text: 'BBB', from: 3, totalChars: 9, hasMore: false })
    const wrapper = mountDrawer({
      live: true, events: [row(1, RESPONSE_TRAIL)],
      doors: { reveal: vi.fn(), fullText },
    })
    // live newest row is expanded by default; the pointer line offers the door
    const openBtn = wrapper.find('[data-test="trail-full-1"]')
    expect(openBtn.exists()).toBe(true)
    await openBtn.trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(fullText).toHaveBeenNthCalledWith(1, 1, 0)
    expect(wrapper.find('[data-test="trail-full-text-1"]').text()).toContain('AAA')
    await wrapper.find('[data-test="trail-full-more-1"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(fullText).toHaveBeenNthCalledWith(2, 1, 3)
    expect(wrapper.find('[data-test="trail-full-text-1"]').text()).toContain('AAABBB')
    expect(wrapper.find('[data-test="trail-full-more-1"]').exists()).toBe(false)
  })

  it('展开全文在飞期间重复点击不二次外呼、分页文本不重复追加', async () => {
    // One page in flight at a time (same guard shape as revealBusy above):
    // a double click while the fetch is pending must be a no-op, otherwise
    // the same cursor is requested twice and the page text lands twice.
    let release: ((page: { text: string; from: number; totalChars: number; hasMore: boolean }) => void) | null = null
    const fullText = vi.fn().mockImplementation(() => new Promise((res) => { release = res }))
    const wrapper = mountDrawer({
      live: true, events: [row(1, RESPONSE_TRAIL)],
      doors: { reveal: vi.fn(), fullText },
    })
    await wrapper.find('[data-test="trail-full-1"]').trigger('click')
    // second click while page one is still pending → ignored by the guard
    await wrapper.find('[data-test="trail-full-1"]').trigger('click')
    expect(fullText).toHaveBeenCalledTimes(1)
    release!({ text: 'AAA', from: 0, totalChars: 9, hasMore: true })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-test="trail-full-text-1"]').text()).toContain('AAA')
    // hammer the continuation button the same way before page two resolves
    await wrapper.find('[data-test="trail-full-more-1"]').trigger('click')
    await wrapper.find('[data-test="trail-full-more-1"]').trigger('click')
    expect(fullText).toHaveBeenCalledTimes(2)
    expect(fullText).toHaveBeenNthCalledWith(2, 1, 3)
    release!({ text: 'BBB', from: 3, totalChars: 9, hasMore: false })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    const body = wrapper.find('[data-test="trail-full-text-1"]').text()
    expect(body).toContain('AAABBB')
    expect(body).not.toContain('BBBAAA')
    expect(wrapper.find('[data-test="trail-full-more-1"]').exists()).toBe(false)
  })

  it('未接门的宿主展开全文钮同样缺席', async () => {
    const wrapper = mountDrawer({ live: true, events: [row(1, RESPONSE_TRAIL)] })
    expect(wrapper.find('[data-test="trail-full-1"]').exists()).toBe(false)
  })
})

describe('TaskTailDrawer 复制只拷打码原文 (T663 slice 4)', () => {
  it('复制钮写剪贴板的永远是打码原文，明文解显后仍不含明文', async () => {
    const writeText = vi.fn(async () => {})
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText }, configurable: true,
    })
    const { api } = doorsFixture()
    const wrapper = mountDrawer({ live: false, events: [row(1, REQUEST_TRAIL)], doors: api })
    await wrapper.find('[data-test="trail-toggle-1"]').trigger('click')
    await wrapper.find('[data-test="trail-reveal-request:authorization"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    await wrapper.find('[data-test="trail-copy-1"]').trigger('click')
    expect(writeText).toHaveBeenCalledTimes(1)
    const copied = writeText.mock.calls[0][0] as string
    expect(copied).toContain('（总长度 42）')
    expect(copied).not.toContain('super-secret')
  })
})

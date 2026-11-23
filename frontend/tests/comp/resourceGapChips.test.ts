
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import type { ExtractionEvent } from '@/api/taskEvents'
import { RESOURCE_GAP_PREFIX, extractResourceGaps } from '@/api/taskEvents'
import ResourceGapChips from '@/components/ResourceGapChips.vue'

function ev(cursor: number, level: string, summary: string): ExtractionEvent {
  return { cursor, level, summary, detail: null, landedAt: '2026-11-11T12:00:00' }
}

describe('extractResourceGaps', () => {
  it('只收 step 级前缀行并剥前缀，混排其余行零污染', () => {
    // Ledger contract (ai-service): declarations land as level=step rows only;
    // the same text on other levels is negative noise and must not surface.
    const rows = [
      ev(1, 'step', 'AI 脚本已写入用例（caseId 7）'),
      ev(2, 'step', RESOURCE_GAP_PREFIX + '登录组件——需要验证码输入'),
      ev(3, 'step', RESOURCE_GAP_PREFIX + '数据工厂'),
      ev(4, 'thinking', RESOURCE_GAP_PREFIX + '不应计入'),
      ev(5, 'error', RESOURCE_GAP_PREFIX + '不应计入'),
    ]
    const gaps = extractResourceGaps(rows)
    expect(gaps).toHaveLength(2)
    expect(gaps[0]).toEqual({ cursor: 2, text: '登录组件——需要验证码输入' })
    expect(gaps[1]).toEqual({ cursor: 3, text: '数据工厂' })
    expect(gaps.map(g => g.cursor)).toEqual([2, 3])
  })

  it('无申报/空清单=空数组（老形态零漂移）', () => {
    expect(extractResourceGaps([])).toEqual([])
    expect(extractResourceGaps([ev(1, 'step', '普通步骤行')])).toEqual([])
  })
})

async function factory(events: ExtractionEvent[], dismissed = false) {
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(ResourceGapChips, {
    props: { events, dismissed },
    global: { plugins: [ElementPlus, i18n] },
  })
}

describe('ResourceGapChips', () => {
  it('有申报渲染芯片行：每类目一枚芯片+两枚一键动作', async () => {
    const w = await factory([
      ev(1, 'step', RESOURCE_GAP_PREFIX + '接口集'),
      ev(2, 'step', RESOURCE_GAP_PREFIX + '数据工厂——查用户表'),
    ])
    expect(w.find('[data-test="resource-gap-row"]').exists()).toBe(true)
    const chips = w.findAll('[data-test^="resource-gap-chip-"]')
    expect(chips).toHaveLength(2)
    expect(chips[0].text()).toBe('接口集')
    expect(chips[1].text()).toContain('数据工厂——查用户表')
    expect(w.find('[data-test="resource-gap-regen"]').exists()).toBe(true)
    expect(w.find('[data-test="resource-gap-dismiss"]').exists()).toBe(true)
    await w.find('[data-test="resource-gap-regen"]').trigger('click')
    expect(w.emitted('regen')).toHaveLength(1)
    await w.find('[data-test="resource-gap-dismiss"]').trigger('click')
    expect(w.emitted('dismiss')).toHaveLength(1)
  })

  it('无申报整行缺席；已忽略同样缺席（忽略只隐面不删账）', async () => {
    const empty = await factory([ev(1, 'step', '普通步骤行')])
    expect(empty.find('[data-test="resource-gap-row"]').exists()).toBe(false)
    const withGap = await factory([ev(2, 'step', RESOURCE_GAP_PREFIX + '环境')])
    expect(withGap.find('[data-test="resource-gap-row"]').exists()).toBe(true)
    const ignored = await factory([ev(2, 'step', RESOURCE_GAP_PREFIX + '环境')], true)
    expect(ignored.find('[data-test="resource-gap-row"]').exists()).toBe(false)
  })
})

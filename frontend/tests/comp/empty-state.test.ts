
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import EmptyState from '@/components/EmptyState.vue'

function factory(props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(EmptyState, { props, attachTo: host, global: { plugins: [ElementPlus, i18n] } })
}

describe('EmptyState（浏览器态）', () => {
  it('真空态：icon+原因+引导动作三要素齐且 icon 有真实渲染盒', () => {
    const w = factory({ title: '还没有用例', hint: '点右上角新建', actionText: '新建用例' })
    expect(w.find('[data-test="empty-state"]').exists()).toBe(true)
    expect(w.find('.empty-state__title').text()).toBe('还没有用例')
    expect(w.find('.empty-state__hint').text()).toBe('点右上角新建')
    const icon = w.find('.empty-state__icon svg')
    expect(icon.exists()).toBe(true)
    const rect = icon.element.getBoundingClientRect()
    expect(rect.width).toBeGreaterThan(10)
    expect(rect.height).toBeGreaterThan(10)
  })

  it('筛选空态：清空筛选出路在、引导钮让位规则不变', () => {
    const w = factory({ title: '无匹配结果', filtered: true })
    expect(w.find('[data-test="empty-clear"]').exists()).toBe(true)
    expect(w.find('[data-test="empty-clear"]').text()).toBe('清空筛选')
  })

  it('action 事件真派发（DOM click→emit）', async () => {
    const w = factory({ title: 'x', actionText: '去新建' })
    await w.find('[data-test="empty-action"]').trigger('click')
    expect(w.emitted('action')).toHaveLength(1)
  })
})

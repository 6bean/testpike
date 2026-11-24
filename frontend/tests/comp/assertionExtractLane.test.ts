/**
 * Extract-row source lane behavior contract (AV batch: extraction lane picker).
 * Capability name only — the schedule anchor lives in the docs/ task split.
 *
 * Why a mount test on top of the static digest: the save gate validates `source`,
 * so the editor face must actually drive it. A source scan cannot tell a wired
 * control from a decorative one — this file asserts the rendered DOM and the
 * emitted JSON (Vitest browser mode = real Chromium, same split as the
 * protocol-editor contract):
 * - a saved extract row on the header lane renders the name field and hides the
 *   JsonPath field;
 * - a legacy extract row (no source key) sits on the body lane and re-emits
 *   byte-identically — untouched rows never gain a `source` write-back;
 * - flipping the lane through the real dropdown drives source + name into the
 *   emitted row and drops the JsonPath key on the non-body lanes;
 * - an incomplete header lane names itself on the edit face (inline red line)
 *   instead of passing silently to the save gate;
 * - an unknown lane value fails closed through validateAll naming the three lanes.
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

import AssertionBuilder from '@/components/AssertionBuilder.vue'

async function flush(): Promise<void> {
  await new Promise((resolve) => { setTimeout(resolve, 0) })
  await new Promise((resolve) => { setTimeout(resolve, 0) })
}

function factory(modelValue: string) {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  return mount(AssertionBuilder, {
    props: { modelValue, projectId: 7 },
    global: { plugins: [i18n, ElementPlus] },
  })
}

/** Choose an extraction lane through the select's own v-model channel — the same
 *  event the widget emits on a real click, so the binding is exercised rather
 *  than the dropdown's internal popper plumbing (asserted separately by the
 *  header-lane load case, which proves the field pair renders per lane). */
async function setLane(w: ReturnType<typeof mount>, lane: string): Promise<void> {
  const select = w.findComponent('[data-test="assertion-source-0"]')
  expect(select.exists(), 'the lane select must render on an extract row').toBe(true)
  select.vm.$emit('update:modelValue', lane)
  await flush()
}

describe('extract lane: the rendered editor face equals the save-gate face', () => {
  it('header lane shows the name field and hides JsonPath', async () => {
    const w = factory('[{"type":"extract","source":"header","name":"X-Auth-Token","saveAs":"HTOKEN"}]')
    await flush()
    expect(w.find('[data-test="assertion-extract-name-0"]').exists(), 'header lane must render the name field').toBe(true)
    expect(w.find('[data-test="assertion-path-0"]').exists(), 'header lane must not render JsonPath').toBe(false)
    expect(w.find('[data-test="assertion-source-0"]').text()).toContain('响应头')
  })

  it('legacy body row keeps JsonPath and re-emits without a source key', async () => {
    const source = '[{"type":"extract","path":"$.data.token","saveAs":"TOKEN"}]'
    const w = factory(source)
    await flush()
    expect(w.find('[data-test="assertion-path-0"]').exists()).toBe(true)
    // An untouched load must not dirty the saved JSON: either nothing is
    // emitted, or what is emitted is byte-identical to the input (empty-save
    // idempotence on the edit face).
    const events = w.emitted('update:modelValue')
    expect(events === undefined || events.at(-1)?.[0] === source,
      'loading an untouched form must not rewrite the assertions').toBe(true)
  })

  it('flipping to the cookie lane drives source + name and drops JsonPath', async () => {
    const w = factory('[{"type":"extract","path":"$.data.token","saveAs":"TOKEN"}]')
    await flush()
    await setLane(w, 'cookie')
    expect(w.find('[data-test="assertion-extract-name-0"]').exists(), 'cookie lane must expose the name field').toBe(true)
    expect(w.find('[data-test="assertion-path-0"]').exists(), 'cookie lane must hide JsonPath').toBe(false)
    // Element Plus falls unknown attributes through to the native input, so the
    // anchor can be the <input> itself — resolve the setValue target per posture.
    const anchor = w.find('[data-test="assertion-extract-name-0"]')
    const input = anchor.element.tagName === 'INPUT' ? anchor : anchor.find('input')
    await input.setValue('SESSION')
    await flush()
    const emitted = JSON.parse(String(w.emitted('update:modelValue')?.at(-1)?.[0]))[0]
    expect(emitted.source).toBe('cookie')
    expect(emitted.name).toBe('SESSION')
    expect('path' in emitted, 'non-body lanes must not write JsonPath back').toBe(false)
    expect(emitted.saveAs).toBe('TOKEN')
  })

  it('an incomplete header lane names itself inline before the save gate', async () => {
    const w = factory('[{"type":"extract","source":"header","saveAs":"HTOKEN"}]')
    await flush()
    const error = w.find('[data-test="assertion-error-0"]')
    expect(error.exists(), 'a header lane without a name must be named inline').toBe(true)
    expect(error.text()).toContain('按 header 提取变量还差名称')
  })

  it('validateAll refuses a lane outside the vocabulary (fail-closed edit face)', async () => {
    const w = factory('[{"type":"extract","source":"xml","name":"a","saveAs":"V"}]')
    await flush()
    const message = (w.vm as unknown as { validateAll: () => string | null }).validateAll()
    expect(message).toContain('变量提取来源 source 仅支持 body / header / cookie')
  })
})

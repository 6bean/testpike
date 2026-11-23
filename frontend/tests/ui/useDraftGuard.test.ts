
import { describe, expect, it, vi } from 'vitest'
import { effect, reactive } from 'vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k: string) => k }) }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))

import { useDraftGuard } from '@/composables/useDraftGuard'

describe('useDraftGuard.isDirty：渲染 effect 依赖可追踪（B12 footer 保存钮禁用态随脏实时翻转）', () => {
  it('effect 内读 isDirty：form 变更后 effect 必须重跑且值翻 true', async () => {
    const form = reactive({ stepsJson: '[]' })
    const guard = useDraftGuard(form, () => {})
    const seen: boolean[] = []
    const stop = effect(() => { seen.push(guard.isDirty()) })
    expect(seen).toEqual([false])
    form.stepsJson = '[{"action":"goto"}]'
    await Promise.resolve()
    expect(seen).toEqual([false, true])
    stop()
  })

  it('变更后再 snapshot()：下一次读取回 false（基线语义不破坏）', () => {
    const form = reactive({ a: '1' })
    const guard = useDraftGuard(form, () => {})
    form.a = '2'
    expect(guard.isDirty()).toBe(true)
    guard.snapshot()
    expect(guard.isDirty()).toBe(false)
  })

  it('多字段对象：任一字段变更即脏，恢复原值即不脏（JSON 串级比对回归钉）', () => {
    const form = reactive({ x: '1', y: '2' })
    const guard = useDraftGuard(form, () => {})
    form.y = '9'
    expect(guard.isDirty()).toBe(true)
    form.y = '2'
    expect(guard.isDirty()).toBe(false)
  })
})

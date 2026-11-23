import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { nextTick, reactive } from 'vue'
import { autoDraftKey, peekAutoDraft, useAutoDraft } from '@/composables/useAutoDraft'
import { installLocalStorageStub } from './localStorageStub'

describe('useAutoDraft snapshot 选项（T199 P6 双模写盘源解耦）', () => {
  let store: Map<string, string>

  beforeEach(() => {
    store = installLocalStorageStub()
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    delete (globalThis as Record<string, unknown>).localStorage
  })

  it('snapshot 提供时：写盘内容=snapshot() 返回值（含 form 外字段与实时最新值）', async () => {
    const form = reactive({ stepsJson: '[]' })
    const live = { tab: 'tree' as 'tree' | 'source' }
    useAutoDraft('k-snap', form as unknown as Record<string, unknown>, {
      snapshot: () => ({ stepsJson: form.stepsJson, tab: live.tab }),
    })
    form.stepsJson = '[{"action":"goto"}]'
    live.tab = 'source'
    await nextTick()
    vi.advanceTimersByTime(8000)
    const rec = peekAutoDraft('k-snap')
    expect(rec).not.toBeNull()
    expect(rec!.data.stepsJson).toBe('[{"action":"goto"}]')
    expect(rec!.data.tab).toBe('source')
  })

  it('缺省无 snapshot：写盘=form 本体（既有挂点行为回归钉）', async () => {
    const form = reactive({ title: 'a' })
    useAutoDraft('k-plain', form as unknown as Record<string, unknown>)
    form.title = 'b'
    await nextTick()
    vi.advanceTimersByTime(8000)
    const rec = peekAutoDraft('k-plain')
    expect(rec).not.toBeNull()
    expect(rec!.data).toEqual({ title: 'b' })
  })

  it('enabled 返回 false 时不调度（弹窗关闭/无脏不写盘）', async () => {
    const form = reactive({ x: 1 })
    let on = false
    useAutoDraft('k-off', form as unknown as Record<string, unknown>, { enabled: () => on })
    form.x = 2
    await nextTick()
    vi.advanceTimersByTime(8000)
    expect(store.has('k-off')).toBe(false)
  })

  it('autoDraftKey 支持任意字符串实体/作用域（script-editor 键形态钉）', () => {
    expect(autoDraftKey('script-editor', 'edit', 42)).toBe('tp-auto-draft:script-editor:edit:42')
  })
})

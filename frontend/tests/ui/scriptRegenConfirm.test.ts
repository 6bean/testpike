
import { describe, expect, it, vi } from 'vitest'
import { submitRegenWithOverwriteGuard, type ScriptRegenResult } from '@/utils/scriptRegenConfirm'

function ok(taskId: number): ScriptRegenResult {
  return { taskId }
}
function needConfirm(message: string): ScriptRegenResult {
  return { need_confirm: true, message }
}

describe('submitRegenWithOverwriteGuard：非手编直放行', () => {
  it('无 need_confirm=单次调用返回 taskId，不弹确认', async () => {
    const call = vi.fn(async () => ok(42))
    const confirm = vi.fn(async () => undefined)
    const taskId = await submitRegenWithOverwriteGuard(call, confirm)
    expect(taskId).toBe(42)
    expect(call).toHaveBeenCalledTimes(1)
    expect(call).toHaveBeenCalledWith(false)
    expect(confirm).not.toHaveBeenCalled()
  })

  it('接口异常原样上抛（宿主人话 catch，本件不吞）', async () => {
    const call = vi.fn(async (): Promise<ScriptRegenResult> => {
      throw new Error('403 无权限')
    })
    await expect(submitRegenWithOverwriteGuard(call, vi.fn())).rejects.toThrow('无权限')
  })
})

describe('submitRegenWithOverwriteGuard：need_confirm 覆盖确认链路', () => {
  it('确认后带 force 重试，返回真实 taskId（toast 不再 #undefined）', async () => {
    const call = vi
      .fn<(force: boolean) => Promise<ScriptRegenResult>>()
      .mockResolvedValueOnce(needConfirm('重新生成将覆盖手工编辑内容（当前版本 v2），确认覆盖请再提交并带 force=true'))
      .mockResolvedValueOnce(ok(4764))
    const confirm = vi.fn(async () => undefined)
    const taskId = await submitRegenWithOverwriteGuard(call, confirm)
    expect(taskId).toBe(4764)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(confirm.mock.calls[0][0]).toContain('覆盖手工编辑内容')
    expect(call.mock.calls).toEqual([[false], [true]])
  })

  it('message 缺失时兜底人话非空（服务端违约不弹空窗）', async () => {
    const call = vi
      .fn<(force: boolean) => Promise<ScriptRegenResult>>()
      .mockResolvedValueOnce({ need_confirm: true })
      .mockResolvedValueOnce(ok(1))
    const confirm = vi.fn(async () => undefined)
    const taskId = await submitRegenWithOverwriteGuard(call, confirm)
    expect(taskId).toBe(1)
    expect((confirm.mock.calls[0][0] as string).length).toBeGreaterThan(0)
  })

  it('用户取消=上抛取消（宿主静默处理），零 force 重试、零任务提交', async () => {
    const call = vi.fn(async () => needConfirm('会覆盖 v2'))
    const confirm = vi.fn(async () => {
      throw new Error('cancel')
    })
    await expect(submitRegenWithOverwriteGuard(call, confirm)).rejects.toThrow('cancel')
    expect(call).toHaveBeenCalledTimes(1)
  })

  it('force 后仍 need_confirm（服务端异常形态）=返回 null 不无限循环', async () => {
    const call = vi.fn(async () => needConfirm('仍要确认'))
    const confirm = vi.fn(async () => undefined)
    const taskId = await submitRegenWithOverwriteGuard(call, confirm)
    expect(taskId).toBeNull()
    expect(call).toHaveBeenCalledTimes(2)
    expect(confirm).toHaveBeenCalledTimes(1)
  })

  it('need_confirm 与 taskId 同体（契约违约形态）=以 need_confirm 优先弹窗', async () => {
    const call = vi
      .fn<(force: boolean) => Promise<ScriptRegenResult>>()
      .mockResolvedValueOnce({ need_confirm: true, message: '覆盖确认', taskId: 999 })
      .mockResolvedValueOnce(ok(1000))
    const confirm = vi.fn(async () => undefined)
    const taskId = await submitRegenWithOverwriteGuard(call, confirm)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(taskId).toBe(1000)
  })
})

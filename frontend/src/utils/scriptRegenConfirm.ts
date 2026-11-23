

import type { ScriptRegenResult } from '@/api/case'


export type { ScriptRegenResult }


export async function submitRegenWithOverwriteGuard(
  call: (force: boolean) => Promise<ScriptRegenResult>,
  confirm: (message: string) => Promise<void>,
): Promise<number | null> {
  let res = await call(false)
  if (res.need_confirm) {
    await confirm(res.message || '重新生成将覆盖当前手工编辑的脚本内容，确认继续？')
    res = await call(true)
    if (res.need_confirm) return null
  }
  return res.taskId ?? null
}

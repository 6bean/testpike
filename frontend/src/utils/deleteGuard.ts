import { ElMessage, ElMessageBox } from 'element-plus'
import type { DeleteCheckView } from '@/api/deletecheck'
import { bizErrorMsg } from '@/utils/bizError'


export interface GuardedDeleteOptions<T> {
  
  check: () => Promise<DeleteCheckView>
 
  doDelete: (force: boolean) => Promise<T>
  
  entityName: string
  
  blocked?: boolean
  
  forceHint?: string
  
  forceText?: string
  
  confirmText?: string
}

export async function guardedDelete<T>(opts: GuardedDeleteOptions<T>): Promise<boolean> {
  let view: DeleteCheckView
  try {
    view = await opts.check()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, '删除检查失败'))
    return false
  }
  try {
    if (view.deletable) {
      await opts.doDelete(false)
      return true
    }
    if (opts.blocked) {
      await ElMessageBox.alert(
        `「${opts.entityName}」被以下内容引用，无法删除：\n${view.message}`,
        '无法删除',
        { type: 'warning', confirmButtonText: '知道了' },
      )
      return false
    }
    try {
      await ElMessageBox.confirm(
        `「${opts.entityName}」下还有 ${view.message}。\n${opts.forceHint ?? '删除将级联清理关联数据，且不可恢复。确定继续吗？'}`,
        '删除确认',
        {
          confirmButtonText: opts.forceText ?? '确认级联删除',
          cancelButtonText: '取消',
          type: 'warning',
        },
      )
    } catch {
      return false
    }
    await opts.doDelete(true)
    return true
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, '删除失败'))
    return false
  }
}

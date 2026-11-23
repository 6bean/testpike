
import { ElMessageBox } from 'element-plus'
import { h } from 'vue'
import { BizError } from '@/api/request'

export async function confirmStaleReload(
  err: unknown,
  title: string,
  reload: () => Promise<void>,
  consequence?: string,
): Promise<boolean> {
  if (!(err instanceof BizError) || (err.code !== '40901' && err.code !== '409')) return false
  const message = err.message || '内容已被他人修改，请刷新后重试'
  // W-group rule: when the reload branch discards user work, the caller passes
  // a consequence line and the box spells out what each button costs.
  const body = consequence
    ? h('div', [h('p', message), h('p', { style: 'margin-top:8px' }, consequence)])
    : message
  try {
    await ElMessageBox.confirm(body, title, {
      confirmButtonText: '刷新重载',
      cancelButtonText: '留在本页',
      type: 'warning',
    })
    await reload()
  } catch {
    
  }
  return true
}

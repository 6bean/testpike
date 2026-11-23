import type { ExploreSession } from '@/api/explore'



export type ExploreTagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'


export const EXPLORE_STATUS: Record<number, { key: string; type: ExploreTagType }> = {
  1: { key: 'explore.statusRunning', type: 'primary' },
  2: { key: 'explore.statusDone', type: 'success' },
  3: { key: 'explore.statusBudgetStop', type: 'warning' },
  4: { key: 'explore.statusPartial', type: 'info' },
  5: { key: 'explore.statusCancelled', type: 'danger' },
}


export function exploreStatusOf(status: number): { key: string; type: ExploreTagType } {
  return EXPLORE_STATUS[status] ?? { key: 'explore.statusUnknown', type: 'info' }
}


export function fmtExploreCost(cents: number | null | undefined): string {
  return cents == null ? '-' : `¥${(cents / 100).toFixed(2)}`
}


export function exploreCancelLabel(status: number, t: (key: string) => string): string {
  return status === 1 ? t('explore.cancelConfirmRunning') : t('explore.cancelConfirmTerminal')
}


export function exploreCanCancel(
  row: Pick<ExploreSession, 'createdBy'>,
  auth: { isAdmin: boolean; user: { userId: number } | null },
): boolean {
  return auth.isAdmin || (row.createdBy != null && row.createdBy === auth.user?.userId)
}

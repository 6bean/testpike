/**
 * Approval-chain status tag color (single source).
 *
 * The 5-state approval spectrum (1=draft 2=pending 3=published 4=rejected
 * 5=retired) is shared by the SKILL and tool domains; tag color mapping used
 * to live as a local copy in every view that renders a status tag. Any page
 * that shows an approval status must import this helper — no second mapping.
 */
export function approvalStatusTag(status: number): 'info' | 'warning' | 'success' | 'danger' {
  return status === 3 ? 'success' : status === 2 ? 'warning' : status === 4 ? 'danger' : 'info'
}

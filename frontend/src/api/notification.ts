import { request, type PageResult } from './request'
import { i18n } from '@/utils/i18n'




export interface NotificationItem {
  id: number
  userId: number
  projectId?: number | null
  /** AI_GEN_DONE / AI_GEN_FAILED / SCRIPT_DONE / SCRIPT_FAILED */
  eventType: string
  channel: string
  title: string
  content: string
  status: number
  
  readFlag: number
  createdAt?: string
  
  dedupKey?: string | null
}


export const NOTIFICATION_EVENT_CODES: Record<string, { labelKey: string; type: 'success' | 'danger' | 'info' }> = {
  AI_GEN_DONE: { labelKey: 'notification.evAiGenDone', type: 'success' },
  AI_GEN_FAILED: { labelKey: 'notification.evAiGenFailed', type: 'danger' },
  SCRIPT_DONE: { labelKey: 'notification.evScriptDone', type: 'success' },
  SCRIPT_FAILED: { labelKey: 'notification.evScriptFailed', type: 'danger' },
  AI_REPAIRED: { labelKey: 'notification.evAiRepaired', type: 'success' },
  EXECUTOR_OFFLINE: { labelKey: 'notification.evExecutorOffline', type: 'danger' },
  EXECUTOR_ONLINE: { labelKey: 'notification.evExecutorOnline', type: 'success' },
  EXPORT_DONE: { labelKey: 'notification.evExportDone', type: 'info' },
  DEFECT_CLOSED: { labelKey: 'notification.evDefectClosed', type: 'success' },
  FEEDBACK_RESOLVED: { labelKey: 'notification.evFeedbackResolved', type: 'success' },
  REVIEW_CHANGED: { labelKey: 'notification.evReviewChanged', type: 'info' },
  EXEC_FAILED: { labelKey: 'notification.evExecFailed', type: 'danger' },
  BATCH_FINISHED: { labelKey: 'notification.evBatchFinished', type: 'info' },
  JOURNEY_SEGMENT_FAILED: { labelKey: 'notification.evJourneySegmentFailed', type: 'danger' },
  JOURNEY_RUN_FINISHED: { labelKey: 'notification.evJourneyRunFinished', type: 'success' },
  TESTRAIL_FAILED: { labelKey: 'notification.evTestrailFailed', type: 'danger' },
  BACKLOG_WARNING: { labelKey: 'notification.evBacklogWarning', type: 'danger' },
  LINKAGE_URGE: { labelKey: 'notification.evLinkageUrge', type: 'info' },
  LINKAGE_ESCALATE: { labelKey: 'notification.evLinkageEscalate', type: 'danger' },
  MQ_DLQ_BACKLOG: { labelKey: 'notification.evMqDlqBacklog', type: 'danger' },
  MAINTENANCE: { labelKey: 'notification.evMaintenance', type: 'info' },
  DAILY_DIGEST: { labelKey: 'notification.evDailyDigest', type: 'info' },
  REVIEW_REMIND: { labelKey: 'notification.evReviewRemind', type: 'info' },
  REVIEW_TIMEOUT: { labelKey: 'notification.evReviewTimeout', type: 'danger' },
  LOGIN_LOCKED: { labelKey: 'notification.evLoginLocked', type: 'danger' },
  LOGIN_SESSION_REVOKE: { labelKey: 'notification.evSessionRevoke', type: 'danger' },
  ENV_UNHEALTHY: { labelKey: 'notification.evEnvUnhealthy', type: 'danger' },
  ENV_RECOVERED: { labelKey: 'notification.evEnvRecovered', type: 'success' },
  SKILL_APPROVAL_REQUEST: { labelKey: 'notification.evSkillApprovalRequest', type: 'info' },
  SKILL_APPROVED: { labelKey: 'notification.evSkillApproved', type: 'success' },
  SKILL_APPROVAL_REJECTED: { labelKey: 'notification.evSkillApprovalRejected', type: 'danger' },
  DELETE_APPROVAL_REQUEST: { labelKey: 'notification.evDeleteApprovalRequest', type: 'info' },
  DELETE_APPROVED: { labelKey: 'notification.evDeleteApproved', type: 'success' },
  DELETE_APPROVAL_REJECTED: { labelKey: 'notification.evDeleteApprovalRejected', type: 'danger' },
  TOOL_APPROVAL_REQUEST: { labelKey: 'notification.evToolApprovalRequest', type: 'info' },
  TOOL_APPROVED: { labelKey: 'notification.evToolApproved', type: 'success' },
  TOOL_APPROVAL_REJECTED: { labelKey: 'notification.evToolApprovalRejected', type: 'danger' },
  MENTION: { labelKey: 'notification.evMention', type: 'info' },
  BATCH_FAILED: { labelKey: 'notification.evBatchFailed', type: 'danger' },
  COST_BUDGET_ALERT: { labelKey: 'notification.evCostBudgetAlert', type: 'danger' },
  REPORT_DIGEST: { labelKey: 'notification.evReportDigest', type: 'info' },
  DEFECT_UNASSIGNED: { labelKey: 'notification.evDefectUnassigned', type: 'info' },
  DOC_CHANGE_ANALYZED: { labelKey: 'notification.evDocChangeAnalyzed', type: 'info' },
  SPEC_CHANGE_ANALYZED: { labelKey: 'notification.evSpecChangeAnalyzed', type: 'info' },
  ASSET_UPDATED: { labelKey: 'notification.evAssetUpdated', type: 'info' },
  ASSET_WITHDRAWN: { labelKey: 'notification.evAssetWithdrawn', type: 'danger' },
  JOB_FAILED: { labelKey: 'notification.evJobFailed', type: 'danger' },
  CERT_EXPIRING: { labelKey: 'notification.evCertExpiring', type: 'danger' },
  SPEC_CHANGE_DETECTED: { labelKey: 'notification.evSpecChangeDetected', type: 'info' },
  MONITOR_DOWN: { labelKey: 'notification.evMonitorDown', type: 'danger' },
  MONITOR_RECOVERED: { labelKey: 'notification.evMonitorRecovered', type: 'success' },
  MONITOR_FLAPPING: { labelKey: 'notification.evMonitorFlapping', type: 'info' },
  MONITOR_LOST: { labelKey: 'notification.evMonitorLost', type: 'danger' },
  PERF_REPORT_CARD: { labelKey: 'notification.evPerfReportCard', type: 'info' },
  PERF_SLA_BREACH: { labelKey: 'notification.evPerfSlaBreach', type: 'danger' },
  UPGRADE_STARTED: { labelKey: 'notification.evUpgradeStarted', type: 'info' },
  BATCH_DONE: { labelKey: 'notification.evBatchDone', type: 'info' },
  CANARY_FAILED: { labelKey: 'notification.evCanaryFailed', type: 'danger' },
  ROLLBACK_DONE: { labelKey: 'notification.evRollbackDone', type: 'info' },
  VERSION_UNSUPPORTED: { labelKey: 'notification.evVersionUnsupported', type: 'danger' },
  NET_PROXY_RESIDUAL: { labelKey: 'notification.evNetProxyResidual', type: 'danger' },
  DEVICE_OVERHEAT: { labelKey: 'notification.evDeviceOverheat', type: 'danger' },
  TAKEOVER_REQUEST: { labelKey: 'notification.evTakeoverRequest', type: 'info' },
  TAKEOVER_FORCED: { labelKey: 'notification.evTakeoverForced', type: 'danger' },
  RESERVATION_APPROVED: { labelKey: 'notification.evReservationApproved', type: 'success' },
  RESERVATION_REJECTED: { labelKey: 'notification.evReservationRejected', type: 'danger' },
  RESERVATION_EXPIRED: { labelKey: 'notification.evReservationExpired', type: 'info' },
  RELEASE_CANDIDATE: { labelKey: 'notification.evReleaseCandidate', type: 'info' },
  // throwaway-space expiry held open by blocking references (one daily notice per owner)
  MOCK_SPACE_EXPIRY_DEFERRED: { labelKey: 'notification.evMockSpaceExpiryDeferred', type: 'info' },
  // AO-7 proactive insight : one per-batch failure-cluster reminder —
  // mirrors NotificationEventDict so the subscription panel never shows an
  // unknown code (the P6a contract gate pins the two sets together).
  AI_INSIGHT_CLUSTER: { labelKey: 'notification.evAiInsightCluster', type: 'info' },
  // WE-S7 DOM-drift early warning (element-health account): one notice per
  // element per day when its rolling windows keep warming. Mirrors
  // NotificationEventDict.WEB_ELEMENT_DRIFT — the P6a set pin stays green.
  WEB_ELEMENT_DRIFT: { labelKey: 'notification.evWebElementDrift', type: 'info' },
}


export const NOTIFICATION_EVENT_KEYS: Record<string, string> = Object.fromEntries(
  Object.entries(NOTIFICATION_EVENT_CODES).map(([code, v]) => [code, v.labelKey]),
)


export function eventLabel(eventType: string): string {
  const entry = NOTIFICATION_EVENT_CODES[eventType]
  return entry ? i18n.global.t(entry.labelKey) : eventType
}


export function eventTagType(eventType: string): 'success' | 'danger' | 'info' {
  return NOTIFICATION_EVENT_CODES[eventType]?.type ?? 'info'
}

export interface NotificationQuery {
  readFlag?: number
 
  eventType?: string
  page?: number
  size?: number
  /** Core whitelisted sort key (blank = historical newest-first order). */
  sortBy?: string
  /** asc|desc (anything else falls back to desc on the server). */
  sortDir?: string
}


export function listNotifications(params: NotificationQuery = {}): Promise<PageResult<NotificationItem>> {
  return request<PageResult<NotificationItem>>({ url: '/notifications', method: 'get', params })
}


export function markNotificationRead(id: number): Promise<void> {
  return request<void>({ url: `/notifications/${id}/read`, method: 'put' })
}


export function markAllNotificationsRead(): Promise<{ affected: number }> {
  return request<{ affected: number }>({ url: '/notifications/read-all', method: 'put' })
}


export function notificationTarget(
  item: Pick<NotificationItem, 'eventType'> & { projectId?: number | null; dedupKey?: string | null },
  opts?: { projectId?: number | null },
): string | null {
  const taskId = item.dedupKey && /^(ai-gen|ai-script)/.test(item.dedupKey)
    ? Number(item.dedupKey.split(':').pop())
    : NaN
  if (item.eventType.startsWith('AI_GEN') || item.eventType.startsWith('SCRIPT')) {
    return Number.isFinite(taskId) && taskId > 0 ? `/ai/generate?task=${taskId}` : '/ai/generate'
  }
  if (item.eventType.startsWith('EXECUTOR_')) return '/execution'
  if (item.eventType.startsWith('REVIEW_')) return '/review'
  if (item.eventType.startsWith('ENV_')) {
    const envPid = opts?.projectId ?? item.projectId
    return envPid ? `/projects/${envPid}` : '/projects'
  }
  if (item.eventType === 'DAILY_DIGEST') return '/report'
  if (item.eventType === 'EXPORT_DONE') {
    const pid = opts?.projectId ?? item.projectId
    return pid ? `/report?projectId=${pid}` : '/report'
  }
  if (item.eventType === 'DEFECT_CLOSED') {
    return item.projectId ? `/defects?projectId=${item.projectId}` : '/defects'
  }
  return null
}

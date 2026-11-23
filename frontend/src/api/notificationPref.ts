import { request } from './request'




export interface NotificationPrefItem {
  eventType: string
  
  enabled: boolean
  
  forced: boolean
  
  defaultOff: boolean
  
  updatedAt?: string | null
}


export interface NotificationPrefToggle {
  eventType: string
  enabled: boolean
}


export const PREF_EVENT_GROUPS: Record<string, string> = {
  LOGIN_LOCKED: 'security',
  LOGIN_SESSION_REVOKE: 'security',
  REVIEW_CHANGED: 'review',
  REVIEW_REMIND: 'review',
  REVIEW_TIMEOUT: 'review',
  LINKAGE_URGE: 'review',
  LINKAGE_ESCALATE: 'review',
  MENTION: 'review',
  EXEC_FAILED: 'execution',
  BATCH_FINISHED: 'execution',
  JOURNEY_SEGMENT_FAILED: 'execution',
  JOURNEY_RUN_FINISHED: 'execution',
  EXECUTOR_OFFLINE: 'execution',
  EXECUTOR_ONLINE: 'execution',
  ENV_UNHEALTHY: 'execution',
  ENV_RECOVERED: 'execution',
  MONITOR_DOWN: 'execution',
  MONITOR_RECOVERED: 'execution',
  MONITOR_FLAPPING: 'execution',
  MONITOR_LOST: 'execution',
  // Element-drift notices are the same page-health family as the monitor rows.
  WEB_ELEMENT_DRIFT: 'execution',
  AI_GEN_DONE: 'ai',
  AI_GEN_FAILED: 'ai',
  SCRIPT_DONE: 'ai',
  SCRIPT_FAILED: 'ai',
  AI_REPAIRED: 'ai',
  DEFECT_CLOSED: 'defect',
  FEEDBACK_RESOLVED: 'defect',
  DAILY_DIGEST: 'report',
  EXPORT_DONE: 'report',
  BACKLOG_WARNING: 'report',
  MQ_DLQ_BACKLOG: 'report',
  MAINTENANCE: 'other',
  TESTRAIL_FAILED: 'other',
  SKILL_APPROVAL_REQUEST: 'approval',
  SKILL_APPROVED: 'approval',
  SKILL_APPROVAL_REJECTED: 'approval',
  DELETE_APPROVAL_REQUEST: 'approval',
  DELETE_APPROVED: 'approval',
  DELETE_APPROVAL_REJECTED: 'approval',
  TOOL_APPROVAL_REQUEST: 'approval',
  TOOL_APPROVED: 'approval',
  TOOL_APPROVAL_REJECTED: 'approval',
  BATCH_FAILED: 'execution',
  COST_BUDGET_ALERT: 'report',
  REPORT_DIGEST: 'report',
  DEFECT_UNASSIGNED: 'defect',
  DOC_CHANGE_ANALYZED: 'ai',
  SPEC_CHANGE_ANALYZED: 'ai',
  ASSET_UPDATED: 'ai',
  ASSET_WITHDRAWN: 'ai',
  JOB_FAILED: 'execution',
  CERT_EXPIRING: 'execution',
  SPEC_CHANGE_DETECTED: 'ai',
  PERF_REPORT_CARD: 'report',
  PERF_SLA_BREACH: 'report',
  UPGRADE_STARTED: 'execution',
  BATCH_DONE: 'execution',
  CANARY_FAILED: 'execution',
  ROLLBACK_DONE: 'execution',
  VERSION_UNSUPPORTED: 'execution',
  NET_PROXY_RESIDUAL: 'execution',
  DEVICE_OVERHEAT: 'execution',
  TAKEOVER_REQUEST: 'execution',
  TAKEOVER_FORCED: 'execution',
  RESERVATION_APPROVED: 'execution',
  RESERVATION_REJECTED: 'execution',
  RESERVATION_EXPIRED: 'execution',
  RELEASE_CANDIDATE: 'execution',
  // mock-space expiry deferral notice (sits in the execution group — no new group)
  MOCK_SPACE_EXPIRY_DEFERRED: 'execution',
  // AO-7 per-batch failure insight rides the AI group (same producer family).
  AI_INSIGHT_CLUSTER: 'ai',
}


export const PREF_GROUPS: { key: string; labelKey: string; hintKey?: string }[] = [
  { key: 'security', labelKey: 'notification.grpSecurity', hintKey: 'notification.grpSecurityHint' },
  { key: 'review', labelKey: 'notification.grpReview' },
  { key: 'execution', labelKey: 'notification.grpExecution' },
  { key: 'ai', labelKey: 'notification.grpAi' },
  { key: 'defect', labelKey: 'notification.grpDefect' },
  { key: 'approval', labelKey: 'notification.grpApproval' },
  { key: 'report', labelKey: 'notification.grpReport' },
  { key: 'other', labelKey: 'notification.grpOther' },
]


export function listNotificationPrefs(): Promise<NotificationPrefItem[]> {
  return request<NotificationPrefItem[]>({ url: '/notification-prefs', method: 'get' })
}


export function updateNotificationPrefs(toggles: NotificationPrefToggle[]): Promise<void> {
  return request<void>({ url: '/notification-prefs', method: 'put', data: toggles })
}


export function diffPrefToggles(
  original: NotificationPrefItem[],
  current: NotificationPrefItem[],
): NotificationPrefToggle[] {
  const before = new Map(original.map((i) => [i.eventType, i.enabled]))
  return current
    .filter((i) => before.get(i.eventType) !== i.enabled)
    .map((i) => ({ eventType: i.eventType, enabled: i.enabled }))
}

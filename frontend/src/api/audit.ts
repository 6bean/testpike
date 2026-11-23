import { i18n } from '@/utils/i18n'
import { request } from './request'
import type { PageResult } from './request'



export interface AuditLogView {
  id: number
  operatorId: number | null
  projectId: number | null
  
  objectType: string
  objectId: string
  
  action: string
  
  beforeJson: string | null
  
  afterJson: string | null
  createdAt: string
}

export interface AuditLogQuery {
  operatorId?: number
  objectType?: string
  objectId?: string
  action?: string
  page?: number
  size?: number
  /** Optional server-side sort pair forwarded to the core column whitelist
   * (blank/undefined keeps the historical id DESC newest-first order). */
  sortBy?: string
  sortDir?: string
}


export const AUDIT_OBJECT_TYPES = ["sys_config", "llm_config", "role", "user", "user_import", "case", "case_import", "requirement", "requirement_import", "exec_plan", "defect", "case_defect", "regression_suite", "project", "project_env", "project_member", "data_factory", "report", "test_report", "audit_chain"] as const satisfies readonly string[]


export function auditActionLabel(action: string): string {
  const key = `audit.act.${action}`
  return i18n.global.te(key) ? String(i18n.global.t(key)) : action
}


export function listAuditLogs(query: AuditLogQuery): Promise<PageResult<AuditLogView>> {
  return request<PageResult<AuditLogView>>({
    url: '/audit-logs',
    method: 'get',
    params: query,
  })
}


export interface ChainVerifyResult {
  async: boolean
  taskId: number
  
  status: number
  valid: boolean | null
  brokenAtId: number | null
  checkedCount: number | null
  checkedAt: string | null
}


export function verifyAuditChain(): Promise<ChainVerifyResult> {
  return request<ChainVerifyResult>({
    url: '/audit-logs/chain-verify',
    method: 'post',
  })
}


export function getChainVerifyTask(taskId: number): Promise<ChainVerifyResult> {
  return request<ChainVerifyResult>({
    url: `/audit-logs/chain-verify/task/${taskId}`,
    method: 'get',
  })
}

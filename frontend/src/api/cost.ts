import { request } from './request'




export interface CostDimItem {
  value: string
  amount: number
  calls: number
}


export interface CostOverviewView {
  month: string
  projectId: number | null
  total: CostDimItem
  byProject: CostDimItem[]
  byModel: CostDimItem[]
  byCaller: CostDimItem[]
  byDay: CostDimItem[]
}


export interface BudgetView {
  projectId: number
  budgetCents: number
  alertPct: number
  overridden: boolean
}


export interface BudgetInput {
  budgetCents: number
  alertPct: number
}


export interface BreakOverrideInput {
  reason: string
  hours: number
}


export interface BreakOverrideView {
  projectId: number
  reason: string
  hours: number
  overrideUntil: string
}

/** One attribution row (E9, phase 3): windowTokens = the session's tokens recomputed from the message ledger inside the selected month; sessionTotalTokens = the lifetime session ledger. Both columns travel together so a cross-month drift is readable on the row. */
export interface SessionUsageItem {
  sessionId: number
  projectId: number
  projectName: string
  userName: string
  title: string
  source: number
  assistantTurns: number
  windowTokens: number
  sessionTotalTokens: number
}

/** Paged attribution result (field-for-field mirror of admin ChatSessionUsagePageMirrorView). */
export interface SessionUsagePage {
  list: SessionUsageItem[]
  total: number
  page: number
  pageSize: number
}


export function getCostOverview(month: string, projectId?: number | null) {
  return request<CostOverviewView>({
    url: '/cost/overview',
    method: 'get',
    params: { month, projectId: projectId ?? undefined },
  })
}

/** Paged per-session attribution (pageSize range 1-100 enforced at the core write-side single point; permission posture identical to overview). */
export function getCostSessionUsage(month: string, projectId: number | null | undefined, page: number, pageSize: number) {
  return request<SessionUsagePage>({
    url: '/cost/session-usage',
    method: 'get',
    params: { month, projectId: projectId ?? undefined, page, pageSize },
  })
}


export function getCostBudget(projectId: number) {
  return request<BudgetView>({ url: `/cost/budget/${projectId}`, method: 'get' })
}


export function putCostBudget(projectId: number, data: BudgetInput) {
  return request<BudgetView>({ url: `/cost/budget/${projectId}`, method: 'put', data })
}


export function postBreakOverride(projectId: number, data: BreakOverrideInput) {
  return request<BreakOverrideView>({
    url: `/cost/break-override/${projectId}`,
    method: 'post',
    data,
  })
}


export function centsToYuan(cents: number | null | undefined): string {
  return ((cents ?? 0) / 100).toFixed(2)
}

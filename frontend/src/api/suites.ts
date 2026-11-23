import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'



export interface SuiteView {
  id: number
  projectId: number
  name: string
  caseIds: number[]
  envIds: number[]
  defaultAssigneeIds: number[]
  cronExpr: string | null
  /** Declared mock binding (admin SuiteView mirror; null = the suite runs against the real environment) */
  mockSpaceId?: number | null
  lastRunAt: string | null
  status: number
  statusLabel: string
  caseCount: number
  createdBy: number
  createdByName: string
  createdAt: string
}

export interface SuiteCreatePayload {
  projectId: number
  name: string
  caseIds: number[]
  envIds: number[]
  defaultAssigneeIds: number[]
  cronExpr?: string | null
  status?: number
  /** Declarative mock binding (0/null = unbound; published own-project space only — core gate) */
  mockSpaceId?: number | null
}

export interface SuiteUpdatePayload {
  name: string
  caseIds: number[]
  envIds: number[]
  defaultAssigneeIds: number[]
  cronExpr?: string | null
  status?: number
  /** Mock binding (null = untouched; 0 = unbind back to the real environment) */
  mockSpaceId?: number | null
}


export interface SuiteRunResult {
  planId: number
  planName: string
  batchCount: number
  taskCount: number
  skippedArchived: number
  message: string
}


export const SUITE_STATUS_LABEL: Record<number, string> = {
  get 1() { return td('dict.common.status.enabled') },
  get 2() { return td('dict.common.status.disabled') },
}

export function listSuites(params: {
  projectId: number
  keyword?: string
  status?: number
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}): Promise<PageResult<SuiteView>> {
  return request<PageResult<SuiteView>>({ url: '/suites', method: 'get', params })
}

export function getSuite(id: number): Promise<SuiteView> {
  return request<SuiteView>({ url: `/suites/${id}`, method: 'get' })
}

export function createSuite(payload: SuiteCreatePayload): Promise<SuiteView> {
  return request<SuiteView>({ url: '/suites', method: 'post', data: payload })
}

export function updateSuite(id: number, payload: SuiteUpdatePayload): Promise<SuiteView> {
  return request<SuiteView>({ url: `/suites/${id}`, method: 'put', data: payload })
}

export function deleteSuite(id: number): Promise<void> {
  return request<void>({ url: `/suites/${id}`, method: 'delete' })
}

export function runSuite(id: number): Promise<SuiteRunResult> {
  return request<SuiteRunResult>({ url: `/suites/${id}/run`, method: 'post' })
}

import { td } from '../utils/dictLabel'
import { request } from './request'




export type TraceNodeType = 'REQUIREMENT' | 'CASE' | 'DEFECT'


export interface TraceNodeItem {
  type: TraceNodeType
  id: number
  projectId: number
  label: string
  status?: number | null
  statusText?: string | null
  
  routePath: string
  
  lastBatchId?: number | null
  lastBatchName?: string | null
  lastExecStatus?: number | null
  lastExecTime?: string | null
  
  source?: string | null
}


export interface TraceChainResult {
  root: TraceNodeItem
  nodes: TraceNodeItem[]
  count: number
  truncated: boolean
}


export function getRequirementTraceChain(requirementId: number): Promise<TraceChainResult> {
  return request<TraceChainResult>({ url: `/requirements/${requirementId}/trace-chain`, method: 'get' })
}


export function getCaseTraceChain(caseId: number): Promise<TraceChainResult> {
  return request<TraceChainResult>({ url: `/cases/${caseId}/trace-chain`, method: 'get' })
}


export const EXEC_STATUS_LABEL: Record<number, string> = {
  get 1() { return td('dict.exec.task.pending') },
  get 2() { return td('dict.exec.task.executing') },
  get 3() { return td('dict.exec.task.success') },
  get 4() { return td('dict.exec.task.fail') },
  get 5() { return td('dict.exec.task.timeout') },
  get 6() { return td('dict.exec.task.skipped') },
}


export const TRACE_TYPE_LABEL: Record<TraceNodeType, string> = {
  get REQUIREMENT() { return td('dict.entity.requirement') },
  get CASE() { return td('dict.entity.case') },
  get DEFECT() { return td('dict.entity.defect') },
}

import { request, type PageResult } from './request'




export interface ScriptRepairView {
  id: number
  projectId: number
  caseId: number
  sourceTaskId: number | null
  trialTaskId: number | null
  round: number
  proposalSummary: string | null
  outcome: number
  outcomeLabel: string
  failNote: string | null
  createdAt: string
}


export function listScriptRepairs(params: {
  caseId?: number
  projectId?: number
  page?: number
  size?: number
}): Promise<PageResult<ScriptRepairView>> {
  return request<PageResult<ScriptRepairView>>({ url: '/script-repairs', method: 'get', params })
}

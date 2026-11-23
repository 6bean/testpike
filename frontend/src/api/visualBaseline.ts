import { request } from './request'




export interface VisualBaselineView {
  id: number
  projectId: number
  stepKey: string
  sourceTaskId: number | null
  sourceArtifactKey: string | null
  baselineKey: string | null
  status: number
  statusLabel: string
  diffTolerance: number | null
  confirmedBy: number | null
  createdAt: string
  confirmedAt: string | null
}


export function listVisualBaselines(params: {
  projectId: number
  caseId: number
}): Promise<VisualBaselineView[]> {
  return request<VisualBaselineView[]>({ url: '/visual-baselines', method: 'get', params })
}


export function confirmVisualBaseline(id: number): Promise<VisualBaselineView> {
  return request<VisualBaselineView>({ url: `/visual-baselines/${id}/confirm`, method: 'post' })
}


export function rejectVisualBaseline(id: number): Promise<void> {
  return request<void>({ url: `/visual-baselines/${id}/reject`, method: 'post' })
}


export function getVisualBaselineViewUrl(id: number): Promise<string> {
  return request<string>({ url: `/visual-baselines/${id}/view-url`, method: 'get' })
}

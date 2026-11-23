import { td } from '../utils/dictLabel'
import { request } from './request'




export interface DefectView {
  id: number
  caseId: number
  defectKey: string
  
  defectStatus: string
  
  status: number
  version: number
  createdBy: number
  createdAt?: string
}


export interface DefectLinkPayload {
  caseId: number
  defectKey: string
  defectStatus?: string
}


export interface DefectStatusPayload {
  defectStatus: string
  version: number
}


export const DEFECT_STATUS_LABEL: Record<string, { label: string; type: 'warning' | 'primary' | 'success' }> = {
  open: { get label() { return td('dict.defect.status.open') }, type: 'warning' },
  in_progress: { get label() { return td('dict.defect.status.inProgress') }, type: 'primary' },
  closed: { get label() { return td('dict.defect.status.closed') }, type: 'success' },
}


export const DEFECT_CLOSE_LABEL: Record<number, { label: string; type: 'warning' | 'success' }> = {
  1: { get label() { return td('dict.defect.close.enabled') }, type: 'warning' },
  2: { get label() { return td('dict.defect.close.done') }, type: 'success' },
}


export function linkDefect(data: DefectLinkPayload): Promise<DefectView> {
  return request<DefectView>({ url: '/defects', method: 'post', data })
}


export function updateDefectStatus(id: number, data: DefectStatusPayload): Promise<DefectView> {
  return request<DefectView>({ url: `/defects/${id}/status`, method: 'put', data })
}


export function listDefectsByCase(caseId: number): Promise<DefectView[]> {
  return request<DefectView[]>({ url: '/defects', method: 'get', params: { caseId } })
}


export function rerunHintCase(caseId: number): Promise<number> {
  return request<number>({ url: `/cases/${caseId}/rerun-hint`, method: 'post' })
}

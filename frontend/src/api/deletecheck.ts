import { request } from './request'




export interface DeleteRefInfo {
  type: string
  label: string
  count: number
  message: string
}


export interface DeleteCheckView {
  refs: DeleteRefInfo[]
  deletable: boolean
  
  message: string
}


export function checkProjectDelete(id: number): Promise<DeleteCheckView> {
  return request<DeleteCheckView>({ url: `/delete-check/projects/${id}`, method: 'get' })
}


export function checkEnvDelete(id: number): Promise<DeleteCheckView> {
  return request<DeleteCheckView>({ url: `/delete-check/envs/${id}`, method: 'get' })
}


export function checkRoleDelete(id: number): Promise<DeleteCheckView> {
  return request<DeleteCheckView>({ url: `/delete-check/roles/${id}`, method: 'get' })
}


export function checkPlanDelete(id: number): Promise<DeleteCheckView> {
  return request<DeleteCheckView>({ url: `/delete-check/plans/${id}`, method: 'get' })
}


export function checkTagDelete(id: number): Promise<DeleteCheckView> {
  return request<DeleteCheckView>({ url: `/delete-check/tags/${id}`, method: 'get' })
}


export function checkCaseDelete(id: number): Promise<DeleteCheckView> {
  return request<DeleteCheckView>({ url: `/delete-check/cases/${id}`, method: 'get' })
}

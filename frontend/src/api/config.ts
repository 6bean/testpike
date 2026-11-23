import { request } from './request'



export interface ConfigView {
  id: number
  configKey: string
  configValue: string
  groupName: string
  valueType: string
  encryptFlag: number
  description?: string | null
}

export interface ConfigSavePayload {
  configKey: string
  configValue: string
  groupName: string
  valueType: string
  encryptFlag: number
  description?: string
}


export function listConfigs(groupName?: string): Promise<ConfigView[]> {
  return request<ConfigView[]>({
    url: '/configs',
    method: 'get',
    params: groupName ? { groupName } : {},
  })
}


export function createConfig(payload: ConfigSavePayload): Promise<ConfigView> {
  return request<ConfigView>({ url: '/configs', method: 'post', data: payload })
}


export function updateConfig(id: number, payload: ConfigSavePayload): Promise<ConfigView> {
  return request<ConfigView>({ url: `/configs/${id}`, method: 'put', data: payload })
}


export function deleteConfig(id: number): Promise<void> {
  return request<void>({ url: `/configs/${id}`, method: 'delete' })
}


export interface RetentionItemView {
  configKey: string
  label: string
  days: number
  cutoff: string | null
  count: number
}


export function previewRetentionCleanup(): Promise<RetentionItemView[]> {
  return request<RetentionItemView[]>({ url: '/configs/retention/preview', method: 'post' })
}


export function runRetentionCleanup(): Promise<RetentionItemView[]> {
  return request<RetentionItemView[]>({ url: '/configs/retention/run', method: 'post' })
}

import { postMultipart, request, type ProgressFn } from './request'




export interface PluginItem {
  id: number
  
  name: string
  
  industry: string | null
  
  version: string
  
  enabled: boolean
  
  source: number
  
  manifest: Record<string, unknown> | null
  
  revision: number
}


export interface PluginApplyResult {
  projectId: number
  pluginId: number
  pluginName: string
  paramSnapshot: Record<string, unknown> | null
  
  mountedSkills: string[] | null
  
  notes: string[] | null
}


export interface AppliedPluginItem {
  pluginId: number
  pluginName: string
  
  enabled: boolean
  paramSnapshot: Record<string, unknown> | null
  appliedAt: string
}


export interface AppliedProjectItem {
  projectId: number
  projectName: string
  appliedAt: string
}


export interface PluginQuery {
  industry?: string
  enabled?: boolean
}


export function pluginList(q: PluginQuery = {}): Promise<PluginItem[]> {
  return request<PluginItem[]>({
    url: '/plugins',
    method: 'get',
    params: { industry: q.industry || undefined, enabled: q.enabled },
  })
}


export function pluginRegister(file: File, onProgress?: ProgressFn): Promise<PluginItem> {
  return postMultipart<PluginItem>('/plugins', file, undefined, onProgress)
}


export function pluginSetEnabled(id: number, enabled: boolean): Promise<PluginItem> {
  return request<PluginItem>({
    url: `/plugins/${id}/enable`,
    method: 'put',
    data: { enabled },
  })
}


export function pluginApply(
  projectId: number,
  pluginId: number,
  confirm: boolean,
): Promise<PluginApplyResult> {
  return request<PluginApplyResult>({
    url: `/projects/${projectId}/plugins/${pluginId}/apply`,
    method: 'post',
    data: { confirm },
  })
}


export function pluginUninstall(projectId: number, pluginId: number): Promise<void> {
  return request<void>({
    url: `/projects/${projectId}/plugins/${pluginId}`,
    method: 'delete',
  })
}


export function projectAppliedPlugins(projectId: number): Promise<AppliedPluginItem[]> {
  return request<AppliedPluginItem[]>({
    url: `/projects/${projectId}/plugins`,
    method: 'get',
  })
}


export function pluginDetail(id: number): Promise<PluginItem> {
  return request<PluginItem>({
    url: `/plugins/${id}`,
    method: 'get',
  })
}


export function pluginUpdate(id: number, revision: number, content: string): Promise<PluginItem> {
  return request<PluginItem>({
    url: `/plugins/${id}`,
    method: 'put',
    data: { revision, content },
  })
}


export function pluginAppliedProjects(id: number): Promise<AppliedProjectItem[]> {
  return request<AppliedProjectItem[]>({
    url: `/plugins/${id}/applied-projects`,
    method: 'get',
  })
}

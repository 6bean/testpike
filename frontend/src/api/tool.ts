import { request, type PageResult } from './request'
import { fetchAndDownloadBlob } from '@/utils/exportDownload'




export interface ToolItem {
  id: number
  projectId: number | null
  name: string
  
  type: number
  typeLabel: string
  category: string | null
  description: string | null
  bodyPreview: string | null
  tagsJson: string | null
  inputSchemaJson: string | null
  outputSchemaJson: string | null
  
  execKind: number | null
  execKindLabel: string | null
  execSpecJson: string | null
  timeoutMs: number
  
  status: number
  statusLabel: string
  currentVersion: number
  
  version: number
  createdBy: number
  approvedBy: number | null
  approvedAt: string | null
  rejectReason: string | null
  createdAt: string
  updatedAt: string
}


export interface ToolCreatePayload {
  projectId?: number | null
  name: string
  type: number
  category?: string | null
  description?: string | null
  body?: string | null
  tagsJson?: string | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  execKind?: number | null
  execSpecJson?: string | null
  timeoutMs?: number | null
  submitNow?: boolean
}


export interface ToolUpdatePayload {
  expectedVersion: number
  name?: string | null
  category?: string | null
  description?: string | null
  body?: string | null
  tagsJson?: string | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  execKind?: number | null
  execSpecJson?: string | null
  timeoutMs?: number | null
}


export interface ToolQuery {
  page: number
  size: number
  keyword?: string
  type?: number | null
  status?: number | null
  projectId?: number | null
  category?: string
  sortBy?: string
  sortDir?: 'asc' | 'desc'
}


export function listTools(q: ToolQuery): Promise<PageResult<ToolItem>> {
  return request<PageResult<ToolItem>>({ url: '/tools', method: 'get', params: q })
}


export function createTool(payload: ToolCreatePayload): Promise<ToolItem> {
  return request<ToolItem>({ url: '/tools', method: 'post', data: payload })
}


export function getTool(id: number): Promise<ToolItem> {
  return request<ToolItem>({ url: `/tools/${id}`, method: 'get' })
}


export function updateTool(id: number, payload: ToolUpdatePayload): Promise<ToolItem> {
  return request<ToolItem>({ url: `/tools/${id}`, method: 'put', data: payload })
}


export function deleteTool(id: number, expectedVersion: number): Promise<void> {
  return request<void>({ url: `/tools/${id}`, method: 'delete', params: { expectedVersion } })
}


export function submitTool(id: number, expectedVersion: number): Promise<ToolItem> {
  return request<ToolItem>({ url: `/tools/${id}/submit`, method: 'post', params: { expectedVersion } })
}


export function approveTool(id: number, expectedVersion: number): Promise<ToolItem> {
  return request<ToolItem>({ url: `/tools/${id}/approve`, method: 'post', params: { expectedVersion } })
}


export function rejectTool(id: number, expectedVersion: number, reason: string): Promise<ToolItem> {
  return request<ToolItem>({ url: `/tools/${id}/reject`, method: 'post', data: { expectedVersion, reason } })
}


export function retireTool(id: number, expectedVersion: number): Promise<ToolItem> {
  return request<ToolItem>({ url: `/tools/${id}/retire`, method: 'post', params: { expectedVersion } })
}



export interface ProjectToolItem {
  toolId: number
  
  enabled: number
  boundAt: string | null
  name: string | null
  type: number | null
  typeLabel: string | null
}


export interface ProjectToolBindingItem {
  toolId: number
  enabled?: number | null
}


export interface ProjectToolView {
  list: ProjectToolItem[]
  version: number
}


export function getProjectTools(projectId: number): Promise<ProjectToolView> {
  return request<ProjectToolView>({ url: `/projects/${projectId}/tools`, method: 'get' })
}


export function saveProjectTools(
  projectId: number,
  tools: ProjectToolBindingItem[],
  expectedVersion: number | null,
): Promise<ProjectToolView> {
  return request<ProjectToolView>({
    url: `/projects/${projectId}/tools`,
    method: 'put',
    data: { tools, expectedVersion },
  })
}



export interface ToolInvocationItem {
  id: number
  caller: string
  actorId: number | null
  taskId: number | null
  toolId: number
  serverId: number | null
  toolName: string | null
  
  success: number
  
  durationMs: number | null
  error: string | null
  digestHash: string | null
  createdAt: string
}


export interface ToolInvocationQuery {
  page: number
  size: number
  start?: string
  end?: string
}


export function listToolInvocations(
  id: number,
  q: ToolInvocationQuery,
): Promise<PageResult<ToolInvocationItem>> {
  return request<PageResult<ToolInvocationItem>>({
    url: `/tools/${id}/invocations`,
    method: 'get',
    params: q,
  })
}



export interface ToolTestRunResult {
  toolId: number
  toolName: string
  success: boolean
  output: string | null
  
  errorCategory: string | null
  
  errorCode: number | null
  errorMessage: string | null
  
  durationMs: number
}


export function testRunTool(id: number, input?: string): Promise<ToolTestRunResult> {
  return request<ToolTestRunResult>({
    url: `/tools/${id}/test-run`,
    method: 'post',
    data: input != null ? { input } : {},
  })
}



export interface ToolDetailItem {
  id: number
  projectId: number | null
  name: string
  type: number
  typeLabel: string
  category: string | null
  description: string | null
  body: string | null
  tagsJson: string | null
  inputSchemaJson: string | null
  outputSchemaJson: string | null
  execKind: number | null
  execKindLabel: string | null
  execSpecJson: string | null
  timeoutMs: number
  status: number
  statusLabel: string
  currentVersion: number
  version: number
  createdBy: number
  approvedBy: number | null
  approvedAt: string | null
  rejectReason: string | null
  createdAt: string
  updatedAt: string
}


export interface ToolVersionInfoItem {
  toolId: number
  version: number
  reason: string | null
  statusAtSnapshot: number
  statusLabel: string
  createdBy: number | null
  createdAt: string
  bodyPreview: string | null
}


export interface ToolVersionDetailItem {
  toolId: number
  version: number
  body: string | null
  inputSchemaJson: string | null
  outputSchemaJson: string | null
  execSpecJson: string | null
  timeoutMs: number | null
  statusAtSnapshot: number
  statusLabel: string
  reason: string | null
  createdBy: number | null
  createdAt: string
}


export function getToolDetail(id: number): Promise<ToolDetailItem> {
  return request<ToolDetailItem>({ url: `/tools/${id}/detail`, method: 'get' })
}


export function listToolVersions(id: number, page = 1, size = 20): Promise<PageResult<ToolVersionInfoItem>> {
  return request<PageResult<ToolVersionInfoItem>>({ url: `/tools/${id}/versions`, method: 'get', params: { page, size } })
}


export function getToolVersionDetail(id: number, version: number): Promise<ToolVersionDetailItem> {
  return request<ToolVersionDetailItem>({ url: `/tools/${id}/versions/${version}`, method: 'get' })
}


export function downloadTool(id: number, version?: number | null): Promise<void> {
  const q = version != null ? `?version=${version}` : ''
  return fetchAndDownloadBlob(`/api/v1/tools/${id}/download${q}`, `tool-${id}${version != null ? `-v${version}` : ''}.md`)
}


export function rollbackTool(id: number, payload: { version: number; expectedVersion: number }): Promise<ToolItem> {
  return request<ToolItem>({ url: `/tools/${id}/rollback`, method: 'post', data: payload })
}



export function isWriteSchema(inputSchemaJson: string | null): boolean {
  if (!inputSchemaJson) return false
  try {
    const flag = (JSON.parse(inputSchemaJson) as Record<string, unknown>)?.['x-write']
    if (flag === undefined || flag === false) return false
    return flag === true ? true : typeof flag !== 'boolean'
  } catch {
    return true
  }
}


export interface ToolConfirmTicket {
  token: string
  expiresInSeconds: number
}


export interface ToolInvokePayload {
  taskId?: number | null
  input?: string | null
  confirmToken?: string | null
}


export function issueConfirmToken(id: number): Promise<ToolConfirmTicket> {
  return request<ToolConfirmTicket>({ url: `/tools/${id}/confirm-token`, method: 'post' })
}


export function invokeTool(id: number, payload: ToolInvokePayload): Promise<ToolTestRunResult> {
  return request<ToolTestRunResult>({
    url: `/tools/${id}/invoke`,
    method: 'post',
    data: payload,
  })
}

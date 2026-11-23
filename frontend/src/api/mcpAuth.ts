import { request } from './request'




export interface McpAuthTemplate {
  id: number
  
  name: string
  
  tools: string[]
  remark: string | null
  
  scope: number
  
  projectId: number | null
  createdAt: string
  updatedAt: string
}


export interface McpAuthTemplatePayload {
  name: string
  tools: string[]
  remark?: string | null
  
  scope?: number | null
  projectId?: number | null
}


export interface McpAuthApplyResult {
  updatedServers: number
  details: string[]
}


export interface ProjectMcpAuth {
  projectId: number
  templateId: number
  
  templateName: string
  
  enabled: number
}

export function listMcpAuthTemplates(): Promise<McpAuthTemplate[]> {
  return request<McpAuthTemplate[]>({ url: '/mcp-auth/templates', method: 'get' })
}

export function createMcpAuthTemplate(input: McpAuthTemplatePayload): Promise<McpAuthTemplate> {
  return request<McpAuthTemplate>({ url: '/mcp-auth/templates', method: 'post', data: input })
}

export function updateMcpAuthTemplate(
  id: number,
  input: McpAuthTemplatePayload,
): Promise<McpAuthTemplate> {
  return request<McpAuthTemplate>({ url: `/mcp-auth/templates/${id}`, method: 'put', data: input })
}

export function deleteMcpAuthTemplate(id: number): Promise<void> {
  return request<void>({ url: `/mcp-auth/templates/${id}`, method: 'delete' })
}

export function applyMcpAuthTemplate(
  id: number,
  serverIds: number[],
): Promise<McpAuthApplyResult> {
  return request<McpAuthApplyResult>({
    url: `/mcp-auth/templates/${id}/apply`,
    method: 'post',
    data: { serverIds },
  })
}


export function getProjectMcpAuth(projectId: number): Promise<ProjectMcpAuth | null> {
  return request<ProjectMcpAuth | null>({ url: `/mcp-auth/projects/${projectId}`, method: 'get' })
}

export function putProjectMcpAuth(
  projectId: number,
  input: { templateId: number; enabled: number },
): Promise<ProjectMcpAuth> {
  return request<ProjectMcpAuth>({ url: `/mcp-auth/projects/${projectId}`, method: 'put', data: input })
}

import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'




export interface McpServerItem {
  id: number
  name: string
  endpoint: string
  
  transport: number
  transportLabel: string
  authMasked: string | null
  
  toolsJson: string | null
  
  enabled: number
  enabledLabel: string
  
  healthStatus: number
  healthLabel: string
  healthMessage: string | null
  lastProbeAt: string | null
  createdAt: string
  updatedAt: string
}


export interface McpServerCreatePayload {
  name: string
  endpoint: string
  transport: number
  auth?: string | null
}


export interface McpServerUpdatePayload {
  name?: string | null
  endpoint?: string | null
  transport?: number | null
  auth?: string | null
}


export interface McpProbeResult {
  success: boolean
  
  category: string
  message: string
  server: McpServerItem
}

export interface McpServerQuery {
  keyword?: string
  enabled?: number
  health?: number
  sortBy?: string
  sortDir?: string
  page?: number
  size?: number
}


export function listMcpServers(q: McpServerQuery): Promise<PageResult<McpServerItem>> {
  return request<PageResult<McpServerItem>>({ url: '/mcp-servers', method: 'get', params: q })
}


export function createMcpServer(input: McpServerCreatePayload): Promise<McpServerItem> {
  return request<McpServerItem>({ url: '/mcp-servers', method: 'post', data: input })
}


export function updateMcpServer(id: number, input: McpServerUpdatePayload): Promise<McpServerItem> {
  return request<McpServerItem>({ url: `/mcp-servers/${id}`, method: 'put', data: input })
}


export function deleteMcpServer(id: number): Promise<void> {
  return request<void>({ url: `/mcp-servers/${id}`, method: 'delete' })
}


export function toggleMcpServer(id: number, enabled: number): Promise<McpServerItem> {
  return request<McpServerItem>({ url: `/mcp-servers/${id}/toggle`, method: 'put', params: { enabled } })
}


export function testMcpServer(id: number): Promise<McpProbeResult> {
  return request<McpProbeResult>({ url: `/mcp-servers/${id}/test`, method: 'post' })
}


export function updateMcpTools(id: number, toolNames: string[]): Promise<McpServerItem> {
  return request<McpServerItem>({ url: `/mcp-servers/${id}/tools`, method: 'put', data: { toolNames } })
}


export const MCP_TRANSPORTS = [
  { value: 1, label: 'sse' },
  { value: 2, label: 'streamable-http' },
]


export const MCP_HEALTH_OPTIONS = [
  { value: 0, get label() { return td('dict.mcp.health.untested') } },
  { value: 1, get label() { return td('dict.mcp.health.healthy') } },
  { value: 2, get label() { return td('dict.mcp.health.fail') } },
]

//** tools_json element wire shape (name/enabled/annotations.readOnlyHint). */
interface SnapshotToolElement {
  name?: string
  enabled?: boolean
  annotations?: { readOnlyHint?: boolean }
}


export interface McpSnapshotTool {
  name: string
  enabled: boolean
  readOnly: boolean
}


export function parseSnapshotTools(toolsJson: string | null): McpSnapshotTool[] {
  if (!toolsJson) return []
  try {
    const parsed = JSON.parse(toolsJson) as
      | SnapshotToolElement[]
      | { tools?: SnapshotToolElement[] }
    const arr = Array.isArray(parsed) ? parsed : parsed.tools || []
    return arr
      .filter((t) => !!t?.name)
      .map((t) => ({
        name: t.name as string,
        enabled: t.enabled === true,
        readOnly: t.annotations?.readOnlyHint === true,
      }))
  } catch {
    return []
  }
}

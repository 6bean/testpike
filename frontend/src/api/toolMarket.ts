import { postMultipart, request, type PageResult, type ProgressFn } from './request'
import type { ToolItem } from './tool'




export interface ToolMarketItem {
  id: number
  projectId: number | null
  name: string
  
  type: number
  typeLabel: string
  category: string | null
  statusLabel: string
  currentVersion: number
  authorName: string | null
  downloadCount: number
  refProjectCount: number
  call30dCount: number
  tags: string[] | null
  bodyPreview: string | null
  createdAt: string
}


export interface ToolMarketDownload {
  toolId: number
  format: string
  fileName: string
  objectKey: string
  url: string
}


export interface ToolMarketQuery {
  page: number
  size: number
  keyword?: string
  type?: number | null
  category?: string
  sortBy?: string
  sortDir?: 'asc' | 'desc'
}


export function marketList(q: ToolMarketQuery): Promise<PageResult<ToolMarketItem>> {
  return request<PageResult<ToolMarketItem>>({ url: '/tool-market', method: 'get', params: q })
}


export function marketDownload(id: number, format: 'md' | 'zip'): Promise<ToolMarketDownload> {
  return request<ToolMarketDownload>({
    url: `/tool-market/${id}/download`,
    method: 'get',
    params: { format },
  })
}


export function importTool(file: File, projectId?: number | null, onProgress?: ProgressFn): Promise<ToolItem> {
  const extra: Record<string, string> = {}
  if (projectId != null) {
    extra.projectId = String(projectId)
  }
  return postMultipart<ToolItem>('/tool-market/import', file, extra, onProgress)
}

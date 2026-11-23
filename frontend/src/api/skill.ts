import { postMultipart, request, type PageResult, type ProgressFn } from './request'




export interface SkillMarketItem {
  id: number
  projectId: number | null
  name: string
  
  type: number
  typeLabel: string
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


export interface SkillMarketDownload {
  skillId: number
  format: string
  fileName: string
  objectKey: string
  url: string
}


export interface SkillView {
  id: number
  projectId: number | null
  name: string
  type: number
  typeLabel: string
  status: number
  statusLabel: string
  currentVersion: number
  updatedAt?: string | null
}


export interface SkillMarketQuery {
  page: number
  size: number
  keyword?: string
  type?: number | null
  tagId?: number | null
  sortBy?: string
  sortDir?: 'asc' | 'desc'
}


export function marketList(q: SkillMarketQuery): Promise<PageResult<SkillMarketItem>> {
  return request<PageResult<SkillMarketItem>>({ url: '/skill-market', method: 'get', params: q })
}


export function marketDownload(id: number, format: 'md' | 'zip'): Promise<SkillMarketDownload> {
  return request<SkillMarketDownload>({
    url: `/skill-market/${id}/download`,
    method: 'get',
    params: { format },
  })
}


export function importSkill(file: File, projectId?: number | null, onProgress?: ProgressFn): Promise<SkillView> {
  const extra: Record<string, string> = {}
  if (projectId != null) {
    extra.projectId = String(projectId)
  }
  return postMultipart<SkillView>('/skill-market/import', file, extra, onProgress)
}



export interface ProjectSkillItem {
  skillId: number
  
  role: number
  
  priority: number
 
  enabled: number
  boundAt: string | null
  name: string | null
  typeLabel: string | null
}


export interface ProjectSkillBindingItem {
  skillId: number
  role?: number | null
  priority?: number | null
  enabled?: number | null
}


export interface ProjectSkillView {
  list: ProjectSkillItem[]
  version: number
}


export function getProjectSkills(projectId: number): Promise<ProjectSkillView> {
  return request<ProjectSkillView>({ url: `/projects/${projectId}/skills`, method: 'get' })
}


export function saveProjectSkills(
  projectId: number,
  skills: ProjectSkillBindingItem[],
  expectedVersion: number | null,
): Promise<ProjectSkillView> {
  return request<ProjectSkillView>({
    url: `/projects/${projectId}/skills`,
    method: 'put',
    data: { skills, expectedVersion },
  })
}

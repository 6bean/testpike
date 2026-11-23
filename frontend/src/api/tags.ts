import { request, type PageResult } from './request'




export interface TagItem {
  id: number
  projectId: number
  name: string
  
  type: number
  caseCount: number
}


export interface TagStatsItem {
  tagId: number
  name: string
  type: number
  caseCount: number
  passCount: number
  passRate: number
}

/** Server-side sort options (keys = core whitelist; omitted = historical order). */
export interface TagSortOptions {
  /** core whitelist key (name/type/created_at/case_count); undefined = default order */
  sortBy?: string
  /** asc / desc — anything else falls back to descending */
  sortDir?: string
}


export function listTags(projectId: number, sort?: TagSortOptions): Promise<TagItem[]> {
  return request<TagItem[]>({
    url: '/tags',
    method: 'get',
    params: {
      projectId,
      sortBy: sort?.sortBy,
      sortDir: sort?.sortDir,
    },
  })
}


export function listTagStats(projectId: number): Promise<TagStatsItem[]> {
  return request<TagStatsItem[]>({ url: '/tags/stats', method: 'get', params: { projectId } })
}


export function listVisibleTags(projectIds: number[]): Promise<TagItem[]> {
  return request<TagItem[]>({ url: '/tags/visible', method: 'get', params: { projectIds: projectIds.join(',') } })
}


export function listVisibleTagStats(projectIds: number[]): Promise<TagStatsItem[]> {
  return request<TagStatsItem[]>({ url: '/tags/stats/visible', method: 'get', params: { projectIds: projectIds.join(',') } })
}


export function createTag(payload: { projectId: number; name: string; type?: number }): Promise<TagItem> {
  return request<TagItem>({ url: '/tags', method: 'post', data: payload })
}


export function renameTag(id: number, newName: string): Promise<TagItem> {
  return request<TagItem>({ url: `/tags/${id}`, method: 'put', data: { newName } })
}


export function mergeTags(fromTagId: number, toTagId: number): Promise<void> {
  return request<void>({ url: '/tags/merge', method: 'post', data: { fromTagId, toTagId } })
}


export function deleteTag(id: number): Promise<void> {
  return request<void>({ url: `/tags/${id}`, method: 'delete' })
}


export function setCaseTags(caseId: number, projectId: number, tagIds: number[]): Promise<TagItem[]> {
  return request<TagItem[]>({ url: `/cases/${caseId}/tags`, method: 'put', data: { projectId, tagIds } })
}

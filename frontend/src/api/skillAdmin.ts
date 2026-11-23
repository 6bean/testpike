import { request, type PageResult } from './request'




export interface SkillAdminItem {
  id: number
  
  projectId: number | null
  name: string
  
  type: number
  typeLabel: string
  
  status: number
  statusLabel: string
  
  currentVersion: number
  
  version: number
  bodyPreview: string | null
  frontmatterJson: string | null
  createdBy: number
  approvedBy: number | null
  approvedAt: string | null
  rejectReason: string | null
  createdAt: string
  updatedAt: string
}


export interface SkillCreatePayload {
  projectId?: number | null
  name: string
  type: number
  body: string
  frontmatterJson?: string | null
  submitNow?: boolean
}


export interface SkillUpdatePayload {
  expectedVersion: number
  name?: string | null
  type?: number | null
  body?: string | null
  frontmatterJson?: string | null
}


export const SKILL_TYPE_KEYS: Record<number, string> = {
  1: 'skill.typeCaseCheck', 2: 'skill.typeReqFill', 3: 'skill.typeScriptSpec', 4: 'skill.typeExecSpec', 5: 'skill.typeFree',
}


export const SKILL_STATUS_KEYS: Record<number, string> = {
  1: 'skill.stDraft', 2: 'skill.stPending', 3: 'skill.stPublished', 4: 'skill.stRejected', 5: 'skill.stRetired',
}


export interface SkillRecycleItem {
  id: number
  
  projectId: number | null
  name: string
  type: number
  
  status: number
  currentVersion: number
  version: number
  deletedBy: number | null
  deletedByName: string | null
  deletedAt: string | null
}


export interface SkillAdminQuery {
  page: number
  size: number
  keyword?: string
  type?: number | null
  status?: number | null
  projectId?: number | null
  tagId?: number | null
  sortBy?: string
  sortDir?: 'asc' | 'desc'
}


export interface SkillVersionItem {
  skillId: number
  version: number
  reason: string | null
  statusAtSnapshot: number
  statusLabel: string
  approvedAt: string | null
  createdBy: number | null
  createdAt: string
  bodyPreview: string | null
}


export interface SkillVersionDetail {
  skillId: number
  version: number
  body: string
  frontmatterJson: string | null
  reason: string | null
  statusAtSnapshot: number
  statusLabel: string
  approvedAt: string | null
  createdBy: number | null
  createdAt: string
}


export function listSkills(q: SkillAdminQuery): Promise<PageResult<SkillAdminItem>> {
  return request<PageResult<SkillAdminItem>>({ url: '/skills', method: 'get', params: q })
}


export function createSkill(payload: SkillCreatePayload): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: '/skills', method: 'post', data: payload })
}


export function getSkill(id: number): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: `/skills/${id}`, method: 'get' })
}


export function updateSkill(id: number, payload: SkillUpdatePayload): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: `/skills/${id}`, method: 'put', data: payload })
}


export function deleteSkill(id: number, expectedVersion: number): Promise<void> {
  return request<void>({ url: `/skills/${id}`, method: 'delete', params: { expectedVersion } })
}


export function listSkillRecycleBin(q: {
  projectId?: number | null
  keyword?: string
  page: number
  size: number
}): Promise<PageResult<SkillRecycleItem>> {
  return request<PageResult<SkillRecycleItem>>({ url: '/skills/recycle-bin', method: 'get', params: q })
}


export function restoreSkill(id: number, expectedVersion: number): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: `/skills/${id}/restore`, method: 'put', params: { expectedVersion } })
}


export function submitSkill(id: number, expectedVersion: number): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: `/skills/${id}/submit`, method: 'post', params: { expectedVersion } })
}


export function approveSkill(id: number, expectedVersion: number): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: `/skills/${id}/approve`, method: 'post', params: { expectedVersion } })
}


export function rejectSkill(id: number, expectedVersion: number, reason: string): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: `/skills/${id}/reject`, method: 'post', data: { expectedVersion, reason } })
}


export function retireSkill(id: number, expectedVersion: number): Promise<SkillAdminItem> {
  return request<SkillAdminItem>({ url: `/skills/${id}/retire`, method: 'post', params: { expectedVersion } })
}


export function listSkillVersions(id: number): Promise<SkillVersionItem[]> {
  return request<SkillVersionItem[]>({ url: `/skills/${id}/versions`, method: 'get' })
}


export function getSkillVersion(id: number, version: number): Promise<SkillVersionDetail> {
  return request<SkillVersionDetail>({ url: `/skills/${id}/versions/${version}`, method: 'get' })
}

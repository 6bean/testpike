import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'

export interface Project {
  id: number
  name: string
  code: string
  description?: string | null
  status?: number
  createdAt?: string
  updatedAt?: string
  
  role?: number
 
  jiraProjectId?: string | null
 
  testOwnerId?: number | null
 
  devOwnerId?: number | null
 
  scriptStoragePath?: string | null
 
  recordingStoragePath?: string | null
 
  storageMode?: number
 
  kbEnabled?: number
 
  notifyFeishu?: number
 
  poolQuotaPct?: number | null
  
  pageInspectEnabled?: number
}


export interface ProjectEnv {
  id: number
  projectId: number
  name: string
  envType: number
  baseUrl?: string | null
  configJson?: string | null
  endTypes?: string | null
  browsers?: string | null
  healthStatus?: number
 
  healthCheckedAt?: string | null
 
  healthMsg?: string | null
  versionSnapshot?: string | null
  createdAt?: string
  updatedAt?: string
}


export interface EnvPayload {
  name: string
  envType: number
  baseUrl?: string
  configJson?: string
  endTypes?: string
  browsers?: string
  versionSnapshot?: string
}

export interface ProjectMember {
  id: number
  projectId: number
  userId: number
  username: string
  nickName?: string | null
  role: string
  roleName?: string | null
  roleLabel?: string | null
  
  roles?: string[]
  createdAt?: string
}


export interface UserOption {
  id: number
  username: string
  displayName?: string | null
}

export function getProjects(params: { page?: number; size?: number; keyword?: string; status?: number; sortBy?: string; sortDir?: string } = {}): Promise<PageResult<Project>> {
  return request<PageResult<Project>>({ url: '/projects', method: 'get', params })
}

export function getProject(id: number): Promise<Project> {
  return request<Project>({ url: `/projects/${id}`, method: 'get' })
}


export interface TemplateContent {
  
  skillIds?: number[] | null
  tags?: { name: string; type?: number | null }[] | null
  envs?: { name: string; envType?: number | null; baseUrl?: string | null; endTypes?: string | null; browsers?: string | null }[] | null
  
  roleHints?: string[] | null
}


export interface ProjectTemplate {
  id: number
  name: string
  industry?: string | null
  content?: TemplateContent | null
  createdBy?: number | null
  createdAt?: string
  updatedAt?: string
}


export function getProjectTemplates(): Promise<ProjectTemplate[]> {
  return request<ProjectTemplate[]>({ url: '/project-templates', method: 'get' })
}


export function getProjectTemplate(id: number): Promise<ProjectTemplate> {
  return request<ProjectTemplate>({ url: `/project-templates/${id}`, method: 'get' })
}

export function createProject(data: { name: string; code: string; description?: string; templateId?: number | null }): Promise<number> {
  return request<number>({ url: '/projects', method: 'post', data })
}


export interface ProjectUpdatePayload {
  name?: string
  defaultLang?: string
  defaultPriority?: string
  budgetMonthly?: number | null
  jiraProjectId?: string | null
  testOwnerId?: number | null
  devOwnerId?: number | null
  scriptStoragePath?: string | null
  recordingStoragePath?: string | null
  storageMode?: number | null
 
  kbEnabled?: number | null
 
  notifyFeishu?: number | null
 
  poolQuotaPct?: number | null
  
  pageInspectEnabled?: number | null
}

export function updateProject(id: number, data: ProjectUpdatePayload): Promise<void> {
  return request<void>({ url: `/projects/${id}`, method: 'put', data })
}

export function deleteProject(id: number): Promise<void> {
  return request<void>({ url: `/projects/${id}`, method: 'delete' })
}


export function purgeProject(id: number, force = false): Promise<void> {
  return request<void>({ url: `/projects/${id}/purge`, method: 'delete', params: { force } })
}


export function getArchivedProjects(params: { page?: number; size?: number } = {}): Promise<PageResult<Project>> {
  return request<PageResult<Project>>({ url: '/projects/archived', method: 'get', params })
}


export function restoreProject(id: number): Promise<Project> {
  return request<Project>({ url: `/projects/${id}/restore`, method: 'put' })
}


export function getProjectEnvs(projectId: number): Promise<ProjectEnv[]> {
  return request<ProjectEnv[]>({ url: `/projects/${projectId}/envs`, method: 'get' })
}


export function envTypeTag(type: number): 'success' | 'primary' | 'warning' | 'danger' {
  if (type === 4) return 'danger'
  if (type === 3) return 'warning'
  if (type === 2) return 'primary'
  return 'success'
}


export function envTypeLabel(type: number): string {
  if (type === 4) return td('dict.project.env.prod') as string
  if (type === 3) return td('dict.project.env.stage') as string
  if (type === 2) return td('dict.project.env.test') as string
  return td('dict.project.env.dev') as string
}

export function createProjectEnv(projectId: number, data: EnvPayload): Promise<number> {
  return request<number>({ url: `/projects/${projectId}/envs`, method: 'post', data })
}

export function updateProjectEnv(projectId: number, envId: number, data: EnvPayload): Promise<void> {
  return request<void>({ url: `/projects/${projectId}/envs/${envId}`, method: 'put', data })
}

export function deleteProjectEnv(projectId: number, envId: number, force = false): Promise<void> {
  return request<void>({ url: `/projects/${projectId}/envs/${envId}`, method: 'delete', params: { force } })
}


export interface EnvHealth {
  status: 'unknown' | 'healthy' | 'unhealthy'
  checkedAt: string
  msg: string
  
  tlsMode?: string | null
  
  certIssuer?: string | null
  
  certNotAfter?: string | null
  
  certChainComplete?: boolean | null
  
  certCheckedAt?: string | null
}


export function getEnvHealth(projectId: number, envId: number): Promise<EnvHealth> {
  return request<EnvHealth>({ url: `/projects/${projectId}/envs/${envId}/health`, method: 'get' })
}


export function probeEnvHealth(projectId: number, envId: number): Promise<{ healthy: boolean; msg: string | null }> {
  return request({ url: `/projects/${projectId}/envs/${envId}/health`, method: 'post' })
}


export interface EnvParamItem {
  key: string
  
  value: string
  
  encrypted: boolean
}


export const ENV_PARAM_MASKED_VALUE = '******'


export const ENV_PARAM_KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/


export function listEnvParams(projectId: number, envId: number): Promise<EnvParamItem[]> {
  return request<EnvParamItem[]>({ url: `/projects/${projectId}/envs/${envId}/params`, method: 'get' })
}


export function saveEnvParams(projectId: number, envId: number, params: Record<string, string>): Promise<EnvParamItem[]> {
  return request<EnvParamItem[]>({ url: `/projects/${projectId}/envs/${envId}/params`, method: 'put', data: { params } })
}


export interface EnvConnectionItem {
  id: number
  projectId: number
  envId: number
  name: string
  dialect: string
  jdbcUrl: string
  username: string
  password: string
  readonly: boolean
  maxPool: number
  status: number
}


export function listEnvConnections(projectId: number): Promise<EnvConnectionItem[]> {
  return request<EnvConnectionItem[]>({ url: `/projects/${projectId}/env-connections`, method: 'get' })
}


export function getProjectMembers(projectId: number): Promise<ProjectMember[]> {
  return request<ProjectMember[]>({ url: `/projects/${projectId}/members`, method: 'get' })
}

export function addProjectMember(projectId: number, data: { userId: number; role: number }): Promise<void> {
  return request<void>({ url: `/projects/${projectId}/members`, method: 'post', data })
}


export function updateMemberRole(projectId: number, userId: number, role: number): Promise<void> {
  return request<void>({ url: `/projects/${projectId}/members/${userId}`, method: 'put', data: { role } })
}

export function deleteProjectMember(projectId: number, memberId: number): Promise<void> {
  return request<void>({ url: `/projects/${projectId}/members/${memberId}`, method: 'delete' })
}


export function getUsers(params: { keyword?: string; page?: number; size?: number } = {}): Promise<PageResult<UserOption>> {
  return request<PageResult<UserOption>>({ url: '/users', method: 'get', params })
}



export interface DeployBatchRef {
  batchId: number
  batchNo: string
  batchName: string
  envVersionSnapshot: string
  matched: boolean
  finishedAt?: string | null
}


export interface EnvDeployInfo {
  deployedVersion?: string | null
  deployedAt?: string | null
  deployedBy?: number | null
  deployedByName?: string | null
  batches: DeployBatchRef[]
}


export function getEnvDeployInfo(envId: number): Promise<EnvDeployInfo> {
  return request<EnvDeployInfo>({ url: `/envs/${envId}/deploy-info`, method: 'get' })
}


export function recordEnvDeploy(envId: number, version: string): Promise<EnvDeployInfo> {
  return request<EnvDeployInfo>({ url: `/envs/${envId}/deploy-info`, method: 'put', data: { version } })
}

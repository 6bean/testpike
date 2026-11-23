import { request } from './request'



export interface LlmConfigView {
  id: number
  name: string
  provider: string
  modelName: string
  apiKeyMasked: string | null
  baseUrl: string
  status: number
  isDefault: number
  description?: string | null
 
  promptLimitTokens?: number
 
  modelType?: number
 
  embeddingDimension?: number | null
}

export interface LlmConfigSavePayload {
  name: string
  provider: string
  modelName: string
  apiKey?: string | null
  baseUrl?: string | null
  status: number
  isDefault: number
  description?: string | null
  promptLimitTokens?: number | null
  modelType?: number | null
  embeddingDimension?: number | null
}

export interface LlmOption {
  id: number
  name: string
  provider: string
  modelName: string
  baseUrl: string
}

export interface ProjectLlmConfigView {
  llmConfigId: number | null
  llmConfigName: string | null
  options: LlmOption[]
}


export interface GoldenDriftVerdict {
  id: number
  templateDomain: string
  templateName: string
  candidateVersionId: number
  controlVersionId: number
  packName: string
  packVersion: number | null
  snapshotDigest: string
  sampleTotal: number
  driftedCount: number
  driftPercentPp: number
  thresholdPp: number
  passed: boolean
  note: string | null
  createdAt: string
  
  comparisonKind: string
  
  controlRef: string | null
  
  candidateRef: string | null
}

export interface GoldenDriftPageView {
  rows: GoldenDriftVerdict[]
  total: number
  page: number
  pageSize: number
}


export function listLlmConfigs(modelType?: number | null): Promise<LlmConfigView[]> {
  return request<LlmConfigView[]>({ url: '/llm-configs', method: 'get', params: modelType == null ? undefined : { modelType } })
}


export function createLlmConfig(payload: LlmConfigSavePayload): Promise<LlmConfigView> {
  return request<LlmConfigView>({ url: '/llm-configs', method: 'post', data: payload })
}


export function updateLlmConfig(id: number, payload: LlmConfigSavePayload): Promise<LlmConfigView> {
  return request<LlmConfigView>({ url: `/llm-configs/${id}`, method: 'put', data: payload })
}


export function deleteLlmConfig(id: number): Promise<void> {
  return request<void>({ url: `/llm-configs/${id}`, method: 'delete' })
}


export function getProjectLlmConfig(projectId: number): Promise<ProjectLlmConfigView> {
  return request<ProjectLlmConfigView>({ url: `/projects/${projectId}/llm-config`, method: 'get' })
}


export function updateProjectLlmConfig(projectId: number, llmConfigId: number | null): Promise<ProjectLlmConfigView> {
  return request<ProjectLlmConfigView>({
    url: `/projects/${projectId}/llm-config`,
    method: 'put',
    data: { llmConfigId },
  })
}


export function listRouteReplays(filters: {
  domain?: string | null
  controlRef?: string | null
  candidateRef?: string | null
  page: number
  pageSize: number
}): Promise<GoldenDriftPageView> {
  const params: Record<string, string | number> = { page: filters.page, pageSize: filters.pageSize }
  if (filters.domain) params.domain = filters.domain
  if (filters.controlRef) params.controlRef = filters.controlRef
  if (filters.candidateRef) params.candidateRef = filters.candidateRef
  return request<GoldenDriftPageView>({ url: '/llm-configs/route-replays', method: 'get', params })
}

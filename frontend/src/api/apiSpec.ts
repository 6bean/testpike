import { postMultipart, request } from './request'
import { ht } from '@/utils/dictLabel'
import { SPEC_EXTRACT_TERMINAL_PREFIXES } from '@/utils/serverTextWire'




export interface ApiParameterView {
  name: string
  location: string
  required: boolean
  type: string
}


export interface ApiEndpointView {
  method: string
  path: string
  endpointKey: string
  operationId: string | null
  title: string
  parameters: ApiParameterView[]
  requestContentType: string | null
  requestSchema: string | null
  responseSchemas: Record<string, string>
  securityRefs: string[]
  structureHash: string
}


export interface SpecDiffView {
  added: string[]
  changed: string[]
  removed: string[]
}


export interface ApiSpecView {
  id: number
  projectId: number
  documentId: number | null
  name: string
  specFormat: string
  specTitle: string | null
  contentSha256: string
  specVersion: number
  endpoints: ApiEndpointView[]
  prevSpecId: number | null
  diffSummary: SpecDiffView | null
  createdBy: number | null
  createdAt: string
}


export function uploadApiSpec(
  projectId: number,
  name: string,
  file: File,
  onProgress?: (p: number) => void,
): Promise<ApiSpecView> {
  return postMultipart<ApiSpecView>('/api-specs', file, { projectId: String(projectId), name }, onProgress, 120000)
}


export function listApiSpecs(params: {
  projectId: number
  page?: number
  size?: number
}): Promise<{ list: ApiSpecView[]; total: number }> {
  return request({ url: '/api-specs', method: 'get', params })
}


export function listApiSpecHistory(projectId: number, name: string): Promise<ApiSpecView[]> {
  return request({ url: '/api-specs/history', method: 'get', params: { projectId, name } })
}


export function fetchApiSpecByUrl(projectId: number, name: string, url: string): Promise<ApiSpecView> {
  return request({ url: '/api-specs/fetch-url', method: 'post', data: { projectId, name, url } })
}


export function getApiSpec(id: number): Promise<ApiSpecView> {
  return request({ url: `/api-specs/${id}`, method: 'get' })
}


export interface SpecSubscriptionView {
  id: number
  projectId: number
  name: string
  url: string | null
  checkIntervalMinutes: number
  enabled: boolean
  lastSpecId: number | null
  lastStatus: string | null
  lastError: string | null
  lastCheckAt: string | null
  createdBy: number | null
  createdAt: string
  sourceKind: string | null
  providerProjectId: number | null
  providerSpecName: string | null
}


export interface SpecSubscriptionSavePayload {
  name: string
  url?: string
  checkIntervalMinutes: number
  enabled?: boolean
  sourceKind?: 'url' | 'program_spec'
  providerProjectId?: number | null
  providerSpecName?: string
}
export function createApiSpecSubscription(payload: SpecSubscriptionSavePayload & {
  projectId: number
}): Promise<SpecSubscriptionView> {
  return request({ url: '/api-specs/subscriptions', method: 'post', data: payload })
}


export function listApiSpecSubscriptions(params: {
  projectId: number
  page?: number
  size?: number
}): Promise<{ list: SpecSubscriptionView[]; total: number }> {
  return request({ url: '/api-specs/subscriptions', method: 'get', params })
}


export function updateApiSpecSubscription(
  id: number,
  payload: Partial<SpecSubscriptionSavePayload>,
): Promise<SpecSubscriptionView> {
  return request({ url: `/api-specs/subscriptions/${id}`, method: 'put', data: payload })
}


export function deleteApiSpecSubscription(id: number): Promise<void> {
  return request({ url: `/api-specs/subscriptions/${id}`, method: 'delete' })
}

/**
 * paramsSkeletonJson/bodySkeleton/assertionsJson/negativeAssertionsJson/authHintJson
 */
export interface ApiSpecDraftItemView {
  endpointKey: string
  method: string
  path: string
  title: string
  description: string | null
  tags: string[]
  paramsSkeletonJson: string
  requestContentType: string | null
  bodySkeleton: string
  assertionsJson: string
  negativeAssertionsJson: string
  negativeSelected: boolean
  securityRefs: string[]
  authHintJson: string
  channel: string
  confidence: string
  sourceAnchor: string | null
}


export interface ApiSpecPreviewView {
  id: number
  projectId: number
  specId: number
  status: string
  items: ApiSpecDraftItemView[]
  createdAt: string
}


export interface SpecPreviewApplyView {
  createdRequests: number
  skipped: number
  requestIds: number[]
}


export function generateSpecPreview(specId: number): Promise<ApiSpecPreviewView> {
  return request({ url: `/api-spec-previews/generate/${specId}`, method: 'post' })
}


export function getSpecPreview(id: number): Promise<ApiSpecPreviewView> {
  return request({ url: `/api-spec-previews/${id}`, method: 'get' })
}


export function applySpecPreview(id: number, selectedKeys: string[]): Promise<SpecPreviewApplyView> {
  return request({ url: `/api-spec-previews/${id}/apply`, method: 'post', data: { selectedKeys } })
}


export interface SpecExtractEstimate {
  chunks: number
  estimatedTokens: number
  maxChunks: number
}


export function extractSpecFromDocument(
  documentId: number,
  modelHint?: string | null,
): Promise<SpecExtractEstimate> {
  return request({
    url: '/api-spec-previews/extract-from-document',
    method: 'post',
    data: { documentId, modelHint: modelHint || undefined },
  })
}


export function getSpecPreviewByDocument(documentId: number): Promise<ApiSpecPreviewView> {
  return request({ url: `/api-spec-previews/by-document/${documentId}`, method: 'get' })
}


export function endpointSuggestionText(row: ApiSpecView, ep: ApiEndpointView): string {
  const params = (ep.parameters || [])
    .map((p) => `${p.name}(${p.location}${p.required ? ht('apiSpec.paramRequired') : ht('apiSpec.paramOptional')},${p.type})`)
    .join('; ')
  const asserts = Object.keys(ep.responseSchemas || {})
    .filter((code) => code.startsWith('2'))
    .map((code) => `HTTP ${code}`)
    .join('/')
  const hasSchema = Object.keys(ep.responseSchemas || {}).length > 0
  return ht('apiSpec.sugHead', { method: ep.method, path: ep.path, title: ep.title || ep.operationId || ep.path })
    + ht('apiSpec.sugContract', { name: row.name, version: row.specVersion, params: params || ht('apiSpec.paramNone') })
    + ht('apiSpec.sugAssert', { asserts: asserts || 'HTTP 2xx' })
    + (hasSchema ? ht('apiSpec.sugAssertSchema') : '')
}

/**
 * Terminal-frame prefixes of the channel-B spec-extraction tail — the single
 * source is the server writer side: SpecDraftExtractServiceImpl books the
 * wiring contract test greps both Java literals so a writer-side wording
 * drift fails there instead of silently keeping the tail window open
 * (precedent: isExploreTerminalRow, R62 C-face).
 */
export { SPEC_EXTRACT_TERMINAL_PREFIXES }

export function isSpecExtractTerminalRow(row: { summary: string }): boolean {
  return SPEC_EXTRACT_TERMINAL_PREFIXES.some((prefix) => row.summary.startsWith(prefix))
}

/** Human failure reason behind the terminal frame's bar (null = not a failure row). */
export function specExtractFailureReason(row: { summary: string }): string | null {
  const prefix = SPEC_EXTRACT_TERMINAL_PREFIXES[1]
  return row.summary.startsWith(prefix) ? row.summary.slice(prefix.length) : null
}

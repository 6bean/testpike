import { request } from './request'




export interface ApiTokenItem {
  id: number
  name: string
  
  scopes: string
  fingerprint: string
  
  revoked: number
  lastUsedAt: string | null
  expiresAt: string | null
  /** GA1 pinned project ids ("3,12" ascending form); null/empty = unrestricted */
  projectScope: string | null
}


export interface ApiTokenCreated {
  id: number
  plaintext: string
}


export interface ApiTokenCreateRequest {
  name: string
  scopes: string
  expiresAt?: string | null
  /** GA1 comma-separated numeric project ids to pin the token to;
   *  null/blank = unrestricted. Core owns validation and normalization. */
  projectScope?: string | null
}


export function listApiTokens(): Promise<ApiTokenItem[]> {
  return request<ApiTokenItem[]>({ url: '/api-tokens', method: 'get' })
}


export function createApiToken(input: ApiTokenCreateRequest): Promise<ApiTokenCreated> {
  return request<ApiTokenCreated>({ url: '/api-tokens', method: 'post', data: input })
}


export function revokeApiToken(id: number): Promise<void> {
  return request<void>({ url: `/api-tokens/${id}/revoke`, method: 'delete' })
}

/** Known scope presets (families in api McpToolCatalog; the backend does
 *  not lock the enum — any mcp.* prefixed scope can be issued, the select is
 *  allow-create, and core normalizeScopes owns the authoritative validation).
 *  The four new scopes (doc/gen/script/exec) are declared by the external-agent
 *  tool batch; listing them here only surfaces them in the picker. */
export const API_TOKEN_SCOPE_PRESETS = [
  'mcp.read',
  'mcp.case',
  'mcp.mock',
  'mcp.doc',
  'mcp.gen',
  'mcp.script',
  'mcp.exec',
  'mcp.review',
]

/** i18n key explaining what each preset scope grants. The locale files own the
 *  wording (single source for copy); this map only binds scope -> description. */
export const API_TOKEN_SCOPE_DESC_KEYS: Record<string, string> = {
  'mcp.read': 'apiToken.scopeDescRead',
  'mcp.case': 'apiToken.scopeDescCase',
  'mcp.mock': 'apiToken.scopeDescMock',
  'mcp.doc': 'apiToken.scopeDescDoc',
  'mcp.gen': 'apiToken.scopeDescGen',
  'mcp.script': 'apiToken.scopeDescScript',
  'mcp.exec': 'apiToken.scopeDescExec',
  'mcp.review': 'apiToken.scopeDescReview',
}

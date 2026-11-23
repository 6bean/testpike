import { request, type PageResult } from './request'




export interface KnowledgeDocItem {
  id: number
  
  projectId: number
  
  packId: number
  
  sourceType: string
  
  sourceRef: number | null
  title: string
  content: string
  /** ACTIVE/INACTIVE */
  status: string
  createdAt: string
  updatedAt: string
}


export const KNOWLEDGE_SOURCE_KEYS: Record<string, string> = {
  TEST_CASE: 'knowledge.srcTestCase',
  FEEDBACK: 'knowledge.srcFeedback',
  STYLE: 'knowledge.srcStyle',
  TEST_SPEC: 'knowledge.srcTestSpec',
  DDL_DICT: 'knowledge.srcDdlDict',
  PACK_KNOWLEDGE: 'knowledge.srcPack',
  TERM: 'knowledge.srcTerm',
}


export const KNOWLEDGE_STATUS_KEYS: Record<string, string> = {
  ACTIVE: 'knowledge.stActive',
  INACTIVE: 'knowledge.stInactive',
}


export interface KnowledgeRebuildResult {
  repairedDocIds: number[]
  orphanDeletedDocIds: number[]
  ledgerActiveRows: number
  vectorRowsAfter: number
}

export interface KnowledgeDocQuery {
  projectId?: number | null
  sourceType?: string
  status?: string
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}

/**
 * The optional sort pair orders through the core column whitelist (single gate). */
export function listKnowledgeDocs(q: KnowledgeDocQuery): Promise<PageResult<KnowledgeDocItem>> {
  return request<PageResult<KnowledgeDocItem>>({
    url: '/knowledge/docs',
    method: 'get',
    params: {
      projectId: q.projectId ?? undefined,
      sourceType: q.sourceType || undefined,
      status: q.status || undefined,
      page: q.page,
      size: q.size,
      sortBy: q.sortBy || undefined,
      sortDir: q.sortDir || undefined,
    },
  })
}


export function rebuildKnowledge(): Promise<KnowledgeRebuildResult> {
  return request<KnowledgeRebuildResult>({ url: '/knowledge/rebuild', method: 'post', timeout: 600000 })
}


export interface KnowledgeChunkItemDetail {
  chunkId: string
  chunkIndex: number
  content: string
  
  metadata: Record<string, unknown>
}


export interface KnowledgeDocDetail extends KnowledgeDocItem {
  
  hitCount: number
  chunks: KnowledgeChunkItemDetail[]
}


export interface KnowledgeHitItem {
  id: number
  taskId: number | null
  
  taskTitle: string | null
  scene: string
  chunkIds: string[]
  createdAt: string
}


export function getKnowledgeDoc(id: number): Promise<KnowledgeDocDetail> {
  return request<KnowledgeDocDetail>({ url: `/knowledge/docs/${id}`, method: 'get' })
}


export function listKnowledgeDocHits(id: number, limit = 20): Promise<KnowledgeHitItem[]> {
  return request<KnowledgeHitItem[]>({
    url: `/knowledge/docs/${id}/hits`,
    method: 'get',
    params: { limit },
  })
}

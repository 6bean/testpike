import { request, type PageResult } from './request'




export interface TrainingSampleItem {
  id: number
  
  caseId: number | null
  
  caseNo: string | null
  
  projectId: number | null
  
  sampleType: string
  
  sampleJson: string
  
  sourceVersion: number | null
  
  createdAt: string
}


export const SAMPLE_TYPE_KEYS: Record<string, string> = {
  '\u9a73\u56de': 'sample.typeReject',
  '\u4fee\u6539\u5dee\u5f02': 'sample.typeDiff',
}

export interface TrainingSampleQuery {
  projectId?: number | null
  sampleType?: string
  page?: number
  size?: number
  /** Server-side sort key (core whitelist; undefined = default id DESC). */
  sortBy?: string
  /** asc | desc; anything else falls back to desc on the backend. */
  sortDir?: string
}


export function listTrainingSamples(q: TrainingSampleQuery): Promise<PageResult<TrainingSampleItem>> {
  return request<PageResult<TrainingSampleItem>>({
    url: '/training-samples',
    method: 'get',
    params: {
      projectId: q.projectId ?? undefined,
      sampleType: q.sampleType || undefined,
      page: q.page,
      size: q.size,
      sortBy: q.sortBy || undefined,
      sortDir: q.sortDir || undefined,
    },
  })
}

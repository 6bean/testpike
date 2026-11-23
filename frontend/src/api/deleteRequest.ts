import { request, type PageResult } from './request'




export interface DeleteRequestItem {
  id: number
 
  targetType: number
  targetTypeLabel: string
  targetId: number
  projectId: number
 
  targetSummary: string
  reason: string
  rejectReason: string | null
  applicantId: number
  
  status: number
  statusLabel: string
  approverId: number | null
  handledAt: string | null
  createdAt: string
}

/** Project list (null status = all; page/size paging; sortBy/sortDir pair optional, empty = backend id DESC) */
export function listDeleteRequests(query: {
  projectId: number
  status?: number | null
  page?: number
  size?: number
  /** core delreq SORT_COLUMNS whitelist key (undefined = default order) */
  sortBy?: string
  /** asc | desc; anything else falls back to desc on the backend */
  sortDir?: string
}): Promise<PageResult<DeleteRequestItem>> {
  return request<PageResult<DeleteRequestItem>>({ url: '/delete-requests', method: 'get', params: query })
}


export function approveDeleteRequest(id: number): Promise<DeleteRequestItem> {
  return request<DeleteRequestItem>({ url: `/delete-requests/${id}/approve`, method: 'post' })
}


export function rejectDeleteRequest(id: number, reason: string): Promise<DeleteRequestItem> {
  return request<DeleteRequestItem>({
    url: `/delete-requests/${id}/reject`,
    method: 'post',
    data: { reason },
  })
}

import { postMultipart, request } from './request'



export type CommentHostType = 'review' | 'case' | 'batch' | 'defect'


export interface CommentItem {
  id: number
  hostType: CommentHostType
  hostId: number
  
  source: number
  authorId: number | null
  authorName: string | null
  content: string
  refKey: string | null
  createdAt: string
}


export interface CommentAttachmentItem {
  id: number
  commentId: number
  fileName: string
  objectKey: string
  contentType: string
  sizeBytes: number
  createdAt: string
}


export interface CommentUnreadItem {
  hostType: CommentHostType
  hostId: number
  unreadCount: number
}


export interface StorageGrant {
  key: string
  url: string
}


export function listComments(hostType: CommentHostType, hostId: number): Promise<CommentItem[]> {
  return request<CommentItem[]>({ url: '/comments', method: 'get', params: { hostType, hostId } })
}


export function createComment(payload: {
  hostType: CommentHostType
  hostId: number
  content: string
  mentions?: number[]
}): Promise<{ id: number }> {
  return request<{ id: number }>({ url: '/comments', method: 'post', data: payload })
}


export function unreadCounts(
  hostType: CommentHostType,
  hostIds: number[],
): Promise<CommentUnreadItem[]> {
  return request<CommentUnreadItem[]>({ url: '/comments/unread-count', method: 'get', params: { hostType, hostIds: hostIds.join(',') } })
}


export function markRead(hostType: CommentHostType, hostId: number): Promise<void> {
  return request<void>({ url: '/comments/mark-read', method: 'post', params: { hostType, hostId } })
}


export function uploadAttachment(commentId: number, file: File): Promise<CommentAttachmentItem> {
  return postMultipart<CommentAttachmentItem>(`/comments/${commentId}/attachments`, file)
}


export function listAttachments(commentId: number): Promise<CommentAttachmentItem[]> {
  return request<CommentAttachmentItem[]>({ url: `/comments/${commentId}/attachments`, method: 'get' })
}


export function downloadAttachment(attachmentId: number): Promise<StorageGrant> {
  return request<StorageGrant>({ url: `/comments/attachments/${attachmentId}/download`, method: 'get' })
}

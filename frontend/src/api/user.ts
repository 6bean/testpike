import { ht } from '../utils/dictLabel'
import { request } from './request'
import type { PageResult } from './request'



export interface UserView {
  id: number
  username: string
  displayName: string
  email?: string | null
  phone?: string | null
  
  avatar?: string | null
  
  role: number
  
  status: number
  deleted: number
  
  failCount?: number
  
  lockedUntil?: string | null
  
  lockReason?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface UserQuery {
  keyword?: string
  role?: number
  status?: number
  page?: number
  size?: number
  /** Server-side sort key (core whitelist; undefined = default order) */
  sortBy?: string
  /** asc / desc — anything else falls back to descending */
  sortDir?: string
}

export interface CreateUserPayload {
  username: string
  password: string
  displayName?: string
  email?: string
  phone?: string
  role: number
}

export interface UpdateUserPayload {
  displayName?: string
  email?: string
  phone?: string
  role: number
  status: number
}


export interface ImportItem {
  rowNumber: number
  success: boolean
  reason?: string | null
  entityId?: number | null
}


export interface ImportResult {
  successCount: number
  failCount: number
  items: ImportItem[]
}


export function listUsers(query: UserQuery): Promise<PageResult<UserView>> {
  return request<PageResult<UserView>>({
    url: '/users',
    method: 'get',
    params: {
      keyword: query.keyword,
      role: query.role,
      status: query.status,
      page: query.page ?? 1,
      size: query.size ?? 20,
      sortBy: query.sortBy,
      sortDir: query.sortDir,
    },
  })
}


export function createUser(payload: CreateUserPayload): Promise<number> {
  return request<number>({ url: '/users', method: 'post', data: payload })
}


export function updateUser(id: number, payload: UpdateUserPayload): Promise<UserView> {
  return request<UserView>({ url: `/users/${id}`, method: 'put', data: payload })
}


export function deleteUser(id: number): Promise<UserView> {
  return request<UserView>({ url: `/users/${id}`, method: 'delete' })
}


export function resetPassword(id: number, newPassword: string): Promise<UserView> {
  return request<UserView>({ url: `/users/${id}/reset-password`, method: 'post', data: { newPassword } })
}


export function unlockUser(id: number): Promise<UserView> {
  return request<UserView>({ url: `/users/${id}/unlock`, method: 'post' })
}


export function importUsers(file: File, onProgress?: (percent: number) => void): Promise<ImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  return request<ImportResult>({
    url: '/users/import',
    method: 'post',
    data: fd,
    timeout: 120000,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}


export function retryFailedUsers(file: File, retryRows: number[], onProgress?: (percent: number) => void): Promise<ImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  retryRows.forEach((row) => fd.append('retryRows', String(row)))
  return request<ImportResult>({
    url: '/users/import/retry',
    method: 'post',
    data: fd,
    timeout: 120000,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}


export async function downloadUserTemplate(): Promise<void> {
  const data = await request<string>({ url: '/users/import/template', method: 'get', timeout: 60000 })
  if (!data) {
    throw new Error(ht('http.templateEmpty'))
  }
  const binary = atob(data)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  const blob = new Blob([bytes], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = ht('http.importTemplateUsers')
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

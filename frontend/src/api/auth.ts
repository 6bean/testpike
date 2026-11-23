import { request } from './request'

export interface LoginUser {
  userId: number
  username: string
  nickName: string
  deptName?: string | null
  roles: string[]
  
  avatar?: string | null
  expiresAt?: number
}

export interface LoginResult {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: LoginUser
 
  refreshToken?: string | null
}


export interface LogoutPayload {
  refreshToken?: string | null
}

/** Program lens snapshot (admin record UserProgramView, camelCase 1:1): one entry per
 *  program the user belongs to, carrying the program's visible project ids. */
export interface UserProgramView {
  programId: number
  name: string
  projectIds: number[]
}

export interface MeResult {
  user: LoginUser
  projects: ProjectBrief[]
  /** Optional = tolerate cached older payloads without the field (lens simply stays off). */
  programs?: UserProgramView[]
 
  passwordExpired?: boolean
}

export interface ProjectBrief {
  
  projectId: number
  projectName: string
  projectCode: string
  role?: number
  roleLabel?: string
  defaultLang?: string
}


export function login(username: string, password: string): Promise<LoginResult> {
  return request<LoginResult>({
    url: '/auth/login',
    method: 'post',
    data: { username, password },
  })
}


export async function logout(refreshToken?: string | null): Promise<void> {
  const { getToken } = await import('./request')
  await fetch('/api/v1/auth/logout', {
    method: 'post',
    headers: {
      'Content-Type': 'application/json',
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
    },
    body: JSON.stringify({ refreshToken: refreshToken || null }),
  }).catch(() => undefined)
}


export function getMe(): Promise<MeResult> {
  return request<MeResult>({ url: '/users/me', method: 'get' })
}


export function updateMyProfile(payload: { displayName?: string; avatar?: string }): Promise<LoginResult> {
  return request<LoginResult>({ url: '/users/me', method: 'put', data: payload })
}


export function changeMyPassword(payload: { oldPassword: string; newPassword: string }): Promise<void> {
  return request<void>({ url: '/users/me/password', method: 'put', data: payload })
}

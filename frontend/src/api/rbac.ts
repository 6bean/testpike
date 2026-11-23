import { request } from './request'



export interface RoleView {
  id: number
  name: string
  code: string
  description?: string | null
  
  dataScope: number
  
  status: number
  
  builtin: number
  createdAt?: string
}

export interface PermView {
  code: string
  name: string
}

export interface RoleSavePayload {
  name: string
  code: string
  description?: string | null
  dataScope: number
  status: number
}


export function listRoles(): Promise<RoleView[]> {
  return request<RoleView[]>({ url: '/roles', method: 'get' })
}


export function listPermCatalog(): Promise<PermView[]> {
  return request<PermView[]>({ url: '/roles/perms', method: 'get' })
}


export function createRole(payload: RoleSavePayload): Promise<number> {
  return request<number>({ url: '/roles', method: 'post', data: payload })
}


export function updateRole(id: number, payload: RoleSavePayload): Promise<RoleView> {
  return request<RoleView>({ url: `/roles/${id}`, method: 'put', data: payload })
}


export function deleteRole(id: number): Promise<void> {
  return request<void>({ url: `/roles/${id}`, method: 'delete' })
}


export function listRoleFuncs(id: number): Promise<string[]> {
  return request<string[]>({ url: `/roles/${id}/funcs`, method: 'get' })
}


export function updateRoleFuncs(id: number, permCodes: string[]): Promise<void> {
  return request<void>({ url: `/roles/${id}/funcs`, method: 'put', data: { permCodes } })
}


export function listUserRoles(userId: number): Promise<RoleView[]> {
  return request<RoleView[]>({ url: `/users/${userId}/roles`, method: 'get' })
}


export function assignUserRoles(userId: number, roleIds: number[]): Promise<void> {
  return request<void>({ url: `/users/${userId}/roles`, method: 'put', data: { roleIds } })
}


export function myPerms(): Promise<string[]> {
  return request<string[]>({ url: '/users/me/perms', method: 'get' })
}


export function myPermissions(): Promise<string[]> {
  return request<string[]>({ url: '/me/permissions', method: 'get' })
}



export interface PermItemView {
  
  key: string
  label: string
  
  family: string
  
  functionId: string
}


export interface PermGroupView {
  domain: string
  domainLabel: string
  items: PermItemView[]
}


export interface RolePermOverrideView {
  permKey: string
  effect: number
}


export const PERM_EFFECT = { INHERIT: 0, ALLOW: 1, DENY: 2 } as const


export function listPermissionCatalog(): Promise<PermGroupView[]> {
  return request<PermGroupView[]>({ url: '/permissions/catalog', method: 'get' })
}


export function getRolePermissions(id: number): Promise<RolePermOverrideView[]> {
  return request<RolePermOverrideView[]>({ url: `/roles/${id}/permissions`, method: 'get' })
}


export function saveRolePermissions(
  id: number,
  items: { permKey: string; effect: number }[],
  confirm = false,
): Promise<RolePermOverrideView[]> {
  return request<RolePermOverrideView[]>({
    url: `/roles/${id}/permissions`, method: 'put', data: { items, confirm },
  })
}

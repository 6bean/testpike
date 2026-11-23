import { td } from '../utils/dictLabel'
import { request } from './request'




export type FavoriteEntityType = 'case' | 'requirement' | 'defect' | 'project'


export interface FavoriteItem {
  entityType: FavoriteEntityType
  entityId: number
  title: string
  subtitle?: string | null
  favoritedAt: string
}


export function addFavorite(entityType: FavoriteEntityType, entityId: number): Promise<void> {
  return request<void>({ url: `/favorites/${entityType}/${entityId}`, method: 'put' })
}


export function removeFavorite(entityType: FavoriteEntityType, entityId: number): Promise<{ affected: number }> {
  return request<{ affected: number }>({ url: `/favorites/${entityType}/${entityId}`, method: 'delete' })
}


export function listFavorites(entityType?: FavoriteEntityType): Promise<FavoriteItem[]> {
  const params: Record<string, string> = {}
  if (entityType) params.entityType = entityType
  return request<FavoriteItem[]>({ url: '/favorites', method: 'get', params })
}


export function favoriteTarget(item: Pick<FavoriteItem, 'entityType' | 'entityId' | 'title'>): string {
  switch (item.entityType) {
    case 'case':
      return `/cases/${item.entityId}`
    case 'project':
      return `/projects/${item.entityId}`
    case 'requirement':
      return `/requirements?keyword=${encodeURIComponent(item.title)}`
    default:
      return `/defects?keyword=${encodeURIComponent(item.title)}`
  }
}


export const FAVORITE_TYPE_LABEL: Record<FavoriteEntityType, string> = {
  get case() { return td('dict.entity.case') },
  get requirement() { return td('dict.entity.requirement') },
  get defect() { return td('dict.entity.defect') },
  get project() { return td('dict.entity.project') },
}

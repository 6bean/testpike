import { ref } from 'vue'
import type { FavoriteEntityType, FavoriteItem } from '@/api/favorite'



const STORAGE_KEY = '***'
const MAX_RECENT = 10

function load(): FavoriteItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const arr = JSON.parse(raw)
    if (!Array.isArray(arr)) return []
    return arr.filter(
      (x: any) => x && typeof x.entityType === 'string' && typeof x.entityId === 'number' && typeof x.title === 'string',
    ) as FavoriteItem[]
  } catch {
    return []
  }
}

function save(items: FavoriteItem[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  } catch {
  }
}


export function getRecentVisits(): FavoriteItem[] {
  return load()
}


export function recordVisit(entityType: FavoriteEntityType, entityId: number, title: string): void {
  if (!entityId || !title) return
  const items = load().filter((i) => !(i.entityType === entityType && i.entityId === entityId))
  items.unshift({ entityType, entityId, title, favoritedAt: nowLocal() })
  const next = items.slice(0, MAX_RECENT)
  save(next)
}


export function useRecentVisits() {
  const recent = ref<FavoriteItem[]>(load())

  function clear(): void {
    save([])
    recent.value = []
  }

  return { recent, clear }
}

function nowLocal(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

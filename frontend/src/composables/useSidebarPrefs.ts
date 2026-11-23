

export const SIDEBAR_DEFAULT_WIDTH = 240
export const SIDEBAR_MIN_WIDTH = 200
export const SIDEBAR_MAX_WIDTH = 320
export const SIDEBAR_COLLAPSED_WIDTH = 64


export const SIDEBAR_WIDTH_KEY = 'tp_sidebar_width'
export const SIDEBAR_COLLAPSED_KEY = 'tp_sidebar_collapsed'


const ANON_BUCKET = 'anon'

export function clampSidebarWidth(raw: number): number {
  if (!Number.isFinite(raw)) return SIDEBAR_DEFAULT_WIDTH
  return Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, Math.round(raw)))
}


export function readSidebarWidth(userId: number | string | null | undefined): number {
  const raw = localStorage.getItem(`${SIDEBAR_WIDTH_KEY}:${userId ?? ANON_BUCKET}`)
  if (raw === null) return SIDEBAR_DEFAULT_WIDTH
  return clampSidebarWidth(Number(raw))
}

export function writeSidebarWidth(userId: number | string | null | undefined, width: number): void {
  localStorage.setItem(`${SIDEBAR_WIDTH_KEY}:${userId ?? ANON_BUCKET}`, String(clampSidebarWidth(width)))
}

export function readSidebarCollapsed(userId: number | string | null | undefined): boolean {
  return localStorage.getItem(`${SIDEBAR_COLLAPSED_KEY}:${userId ?? ANON_BUCKET}`) === '1'
}

export function writeSidebarCollapsed(userId: number | string | null | undefined, collapsed: boolean): void {
  localStorage.setItem(`${SIDEBAR_COLLAPSED_KEY}:${userId ?? ANON_BUCKET}`, collapsed ? '1' : '0')
}

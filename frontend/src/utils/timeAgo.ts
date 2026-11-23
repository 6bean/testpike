

const MIN = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}


export function timeAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '-'
  const ts = Date.parse(iso.includes('T') ? iso : iso.replace(' ', 'T'))
  if (Number.isNaN(ts)) return iso.slice(0, 16)
  const diff = now - ts
  if (diff < 0) return '刚刚'
  if (diff < MIN) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MIN)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  const d = new Date(ts)
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (diff < 2 * DAY) return `昨天 ${hm}`
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
}

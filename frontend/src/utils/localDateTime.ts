/**
 * Readable local date-time shape for on-screen copy.
 *
 * Why: `Date.prototype.toLocaleString()` answers "10/31/2026, 2:04:19 PM"
 * (browser-default American shape) even inside a zh interface — a vision
 * pass over the share faces filed it as a real defect. One formatter, one
 * shape (YYYY-MM-DD HH:mm), shared by every surface that stamps a time
 * into user-visible sentences (red line: reused >= 2 times -> single piece).
 */
export function formatLocalDateTime(value: string | number | Date): string {
  const d = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

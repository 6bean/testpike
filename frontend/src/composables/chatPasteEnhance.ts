/**
 * Chat composer paste enhancement .
 *
 * Turns the three clipboard shapes a user actually produces into the right
 * composer behaviour, decided by ONE pure planner so every surface (floating
 * dock, full chat page) shares a single routing table:
 * - `text/html` containing `<table>` (Excel/web copy) → GitHub-flavored
 * Markdown pipe tables in document order, with the prose between/around
 * the tables kept at its original position, in priority 1;
 * - plain `text/plain` shaped like TSV (tabs + multiple lines, terminal or
 * spreadsheet copy without html) → the same conversion, priority 2 only
 * when the clipboard carries no html at all;
 * - anything else (including markdown source) → untouched default paste.
 * Image clipboard items are lifted out for the attachment door regardless of
 * the text branch taken (a screenshot and a table can arrive together).
 *
 * The conversion is hot-updatable through the K8 key
 * ai.chat.paste-table-convert.enabled (factory on). With the key off nothing
 * is rewritten — the planner still reports `tableHit` so the composer can
 * point at the switch once, and the browser pastes the raw text.
 *
 * Parsing posture: clipboard html comes from office apps as flat, well-formed
 * table markup, so a tag tokenizer is enough and stays runnable outside the
 * browser (the L2 tier is pure node, no jsdom). Nested tables degrade to the
 * inner-closest `</table>` boundary — accepted for clipboard input, the raw
 * text always remains available behind the switch.
 */
import { ref } from 'vue'
import { listConfigs } from '@/api/config'
import { useAuthStore } from '@/stores/auth'

export interface ClipboardShapes {
  html: string
  text: string
  images: File[]
}

export interface PastePlan {
  /** Image clipboard items destined for the attachment queue. */
  images: File[]
  /** Converted table text to insert at the caret; non-null only when consumed. */
  insertText: string | null
  /** True = the default paste must be prevented (table converted on insert). */
  consumed: boolean
  /** True = the clipboard carried convertible table shapes (used for the off-state pointer). */
  tableHit: boolean
}

/** Hot-update key literal — must match the seed row (single wording source). */
export const PASTE_TABLE_KEY = 'ai.chat.paste-table-convert.enabled'

const HTML_ENTITIES: Record<string, string> = {
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&apos;': "'",
  '&nbsp;': ' ',
}

function decodeEntities(s: string): string {
  return s.replace(/&(?:amp|lt|gt|quot|apos|nbsp);/g, (m) => HTML_ENTITIES[m] ?? m)
}

/** One cell: strip markup, keep <br> as a line break, escape pipes for GFM. */
function cellText(innerHtml: string): string {
  const broken = innerHtml.replace(/<br\s*\/?>/gi, '\n')
  const stripped = decodeEntities(broken.replace(/<[^>]*>/g, ''))
  return stripped.replace(/\r/g, '').trim().replace(/\|/g, '\\|').replace(/\n/g, '<br>')
}

/** One plain-text cell (TSV branch): trim + pipe escape, no markup involved. */
function plainCell(raw: string): string {
  return raw.trim().replace(/\|/g, '\\|')
}

/** Rows → GFM block: widest row wins the column count, short rows pad empty. */
function renderGfm(rows: string[][]): string | null {
  const data = rows.filter((r) => r.length > 0)
  if (data.length === 0) return null
  const cols = Math.max(...data.map((r) => r.length))
  const line = (r: string[]) => `| ${[...r, ...Array.from({ length: cols - r.length }, () => '')].join(' | ')} |`
  const sep = `| ${Array.from({ length: cols }, () => '---').join(' | ')} |`
  const [head, ...body] = data
  return [line(head), sep, ...body.map(line)].join('\n')
}

/** Prose between tables: tags off, entities decoded, whitespace collapsed.
 *  Whitespace-only gaps stay empty here and are dropped by the caller so the
 *  insert never floods with blank lines. */
function proseSegment(rawHtml: string): string {
  const broken = rawHtml.replace(/<br\s*\/?>/gi, '\n')
  return decodeEntities(broken.replace(/<[^>]*>/g, '')).replace(/\s+/g, ' ').trim()
}

/**
 * Every `<table>` in document order → GFM blocks, with the prose that sits
 * outside the tables kept at its original position (Excel/web copies carry
 * captions between tables; dropping them would silently lose content).
 * Blocks and prose segments are joined by a blank line in document order.
 * Null when no table (or none with cells) is present — a table-less clipboard
 * must fall through to the default paste, never to a prose rewrite.
 */
export function htmlTablesToGfm(html: string): string | null {
  if (!/<table[\s>]/i.test(html)) return null
  const parts: string[] = []
  let converted = 0
  let cursor = 0
  const tableRe = /<table[^>]*>([\s\S]*?)<\/table>/gi
  let table: RegExpExecArray | null
  while ((table = tableRe.exec(html)) !== null) {
    const before = proseSegment(html.slice(cursor, table.index))
    if (before) parts.push(before)
    cursor = tableRe.lastIndex
    const rows: string[][] = []
    const rowRe = /<tr[^>]*>([\s\S]*?)<\/tr>/gi
    let row: RegExpExecArray | null
    while ((row = rowRe.exec(table[1])) !== null) {
      const cells: string[] = []
      const cellRe = /<t([dh])[^>]*>([\s\S]*?)<\/t\1>/gi
      let cell: RegExpExecArray | null
      while ((cell = cellRe.exec(row[1])) !== null) cells.push(cellText(cell[2]))
      if (cells.length > 0) rows.push(cells)
    }
    const gfm = renderGfm(rows)
    if (gfm) {
      parts.push(gfm)
      converted++
    }
  }
  const after = proseSegment(html.slice(cursor))
  if (after) parts.push(after)
  return converted > 0 ? parts.join('\n\n') : null
}

/**
 * TSV-shaped text (≥2 lines, at least one tab) → GFM. The trailing empty line
 * most spreadsheets append never counts as a row.
 */
export function tsvToGfm(text: string): string | null {
  const lines = text.replace(/\r/g, '').split('\n')
  while (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()
  if (lines.length < 2 || !lines.some((l) => l.includes('\t'))) return null
  return renderGfm(lines.map((l) => l.split('\t').map(plainCell)))
}

/**
 * Route one clipboard snapshot through the three shapes. Pure — the caller
 * owns preventDefault/insert/upload side effects, which keeps the whole
 * spectrum testable without a DOM.
 */
export function planChatPaste(clip: ClipboardShapes, enabled: boolean): PastePlan {
  const images = clip.images ?? []
  const html = clip.html ?? ''
  const text = clip.text ?? ''
  // Priority 1: html tables. Priority 2: TSV twin, only when no html came along.
  const converted = htmlTablesToGfm(html) ?? (html ? null : tsvToGfm(text))
  const tableHit = converted != null
  const consumed = enabled && tableHit
  return { images, insertText: consumed ? converted : null, consumed, tableHit }
}

/** Factory default on (install-and-use); the switch view is a shared singleton. */
const pasteTableConvertEnabled = ref(true)
let switchLoadStarted = false

export function pasteTableConvertEnabledView() {
  return pasteTableConvertEnabled
}

/**
 * Read the key once per session through the existing grouped config query
 * page permission keep the factory default — the same zero-config posture
 * every R32 feature takes. Any read failure fails soft to the default.
 */
export async function loadPasteTableConvertSwitch(): Promise<void> {
  if (switchLoadStarted) return
  // Without the permission (or before the profile lands) do NOT latch — the
  // identity watch retries once the actor is known.
  if (!useAuthStore().hasPerm('perm:system:system-config')) return
  switchLoadStarted = true
  try {
    // Group literal = the tp_sys_config seed contract (\u7cfb\u7edf = the
    // shared "system" group StepTree's usage bar reads); wire value, not UI text.
    const rows = await listConfigs('\u7cfb\u7edf')
    const raw = rows.find((r) => r.configKey === PASTE_TABLE_KEY)?.configValue
    if (raw === 'false' || raw === '0') pasteTableConvertEnabled.value = false
    else pasteTableConvertEnabled.value = true
  } catch {
    pasteTableConvertEnabled.value = true
  }
}

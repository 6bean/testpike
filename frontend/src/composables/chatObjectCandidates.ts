/**
 * @-object mention candidates for the dialog input .
 *
 * Red lines pinned here (TC-AO-05 posture: reuse, never rebuild):
 * - candidate sources are EXISTING read doors only — cases/defects ride the
 * global-search endpoint (already user-scoped, H1 isolation), batches ride
 * the batch list, knowledge docs ride the ledger list. No new backend door,
 * the browser never talks to ai-service directly;
 * - the CHIP is the single truth of a reference: the send payload derives
 * from the chip list via toRefPayload; typed "@text" that was never
 * accepted as a chip stays plain text and is never parsed as a reference;
 * - one door being down degrades honestly: other groups still land, the
 * popup shows what exists instead of going blank (never a fake full set).
 */
import { globalSearch } from '@/api/search'
import { listBatches } from '@/api/batch'
import { listKnowledgeDocs } from '@/api/knowledge'
import { fetchMentionCandidates } from '@/api/aiChat'
import type { RefObjectPayload } from '@/api/aiChat'

export type MentionKind = RefObjectPayload['kind']

/** One selectable row of the mention popup (kind + id + human title). */
export interface MentionItem {
  kind: MentionKind
  id: number
  title: string
}

/** Per-group head size: the popup shows at most 10 rows per kind (SE search rules). */
const GROUP_SIZE = 10

function fallbackTitle(item: { id: number; title: string | null; subtitle?: string | null }): string {
  return item.title ?? item.subtitle ?? `#${item.id}`
}

/**
 * Assemble candidate groups for one keyword inside one project segment.
 * Search/batch endpoints filter server-side; the knowledge door has no
 * keyword parameter, so it returns the segment head and the popup filters the
 * titles client-side (no invented endpoint).
 */
export async function assembleMentionCandidates(keyword: string, projectId: number): Promise<MentionItem[]> {
  const items: MentionItem[] = []
  // One settled quartet: each group lands independently; a down door drops
  // its group (honest partial), it never poisons the others. The skill/tool
  // door answers with the core assembly rows themselves — skills
  // before tools — so the palette offers exactly what the run can consume.
  const [search, batches, docs, mention] = await Promise.allSettled([
    globalSearch(keyword),
    listBatches({ projectId, keyword, page: 1, size: GROUP_SIZE }),
    listKnowledgeDocs({ projectId, page: 1, size: GROUP_SIZE }),
    fetchMentionCandidates(projectId),
  ])
  if (search.status === 'fulfilled') {
    for (const c of search.value.cases.slice(0, GROUP_SIZE)) {
      items.push({ kind: 'case', id: c.id, title: fallbackTitle(c) })
    }
    for (const d of search.value.defects.slice(0, GROUP_SIZE)) {
      items.push({ kind: 'defect', id: d.id, title: fallbackTitle(d) })
    }
  }
  if (batches.status === 'fulfilled') {
    for (const b of batches.value.list.slice(0, GROUP_SIZE)) {
      items.push({ kind: 'batch', id: b.id, title: b.name })
    }
  }
  if (docs.status === 'fulfilled') {
    for (const doc of docs.value.list.slice(0, GROUP_SIZE)) {
      items.push({ kind: 'doc', id: doc.id, title: doc.title })
    }
  }
  if (mention.status === 'fulfilled') {
    // Server order is kept verbatim (skill block then tool block) and each
    // family rides the same per-group head bound as the other doors, so a
    // hundred-row assembly never turns the palette into a bare long list.
    const seen: Record<string, number> = {}
    for (const c of mention.value) {
      seen[c.kind] = (seen[c.kind] ?? 0) + 1
      if (seen[c.kind] > GROUP_SIZE) continue
      items.push(c)
    }
  }
  return items
}

/** Accept one popup row as the reference payload riding the stream body. */
export function toRefPayload(item: MentionItem): RefObjectPayload {
  return { kind: item.kind, id: item.id, title: item.title }
}

/**
 * Chip list is the single truth: re-accepting the same kind+id replaces the
 * older chip (title may have been renamed) at its original position, so the
 * send payload carries each object exactly once.
 */
export function dedupeRefs(refs: RefObjectPayload[]): RefObjectPayload[] {
  const byKey = new Map<string, RefObjectPayload>()
  for (const r of refs) {
    byKey.set(`${r.kind}:${r.id}`, r)
  }
  return [...byKey.values()]
}

/** Client-side title filter for the popup (search door already filters its own groups). */
export function filterMentionItems(items: MentionItem[], token: string): MentionItem[] {
  const kw = token.trim().toLowerCase()
  if (kw === '') return items
  return items.filter((i) => i.title.toLowerCase().includes(kw) || String(i.id) === kw)
}

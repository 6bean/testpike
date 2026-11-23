/**
 * WE-S7 ledger health board — presentation-only derivations for the cards
 * above the element ledger table (storm cards, self-heal rate, trial-run
 * suggestions). The server owns every judgement (core step-health account,
 * storm aggregation in WebElementStormService); this module only maps
 * readings to view models, so the page never recomputes a badge or pools
 * two different signals into one count (R40 single source, R25 scope).
 */
import type {
  RevisionStormRow,
  StepHealEvidenceRow,
  WebElementHealthRow,
} from '@/api/webElements'

/** Element name shown on a storm card; unknown ids keep their id so no
 *  affected entry is ever silently dropped from the list. */
export interface StormCardEntry {
  id: number
  name: string
}

/** One storming page rendered as its own card. The two lists stay side by
 * to break) answer different questions. */
export interface StormCard {
  pageKey: string
  stale: StormCardEntry[]
  breaking: StormCardEntry[]
}

export type HealthBadgeTag = 'success' | 'warning' | 'danger' | 'info'

/** Badge → el-tag color (moved out of the view so the contract test pins
 *  the exact code table: green/yellow/red/none → success/warning/danger/info). */
export function healthTagType(badge: WebElementHealthRow['badge']): HealthBadgeTag {
  if (badge === 'green') return 'success'
  if (badge === 'yellow') return 'warning'
  if (badge === 'red') return 'danger'
  return 'info'
}

/** Turn storm rows into per-page cards. `nameById` comes from the already
 *  loaded ledger groups — no extra request, no second data channel. */
export function buildStormCards(
  storms: RevisionStormRow[],
  nameById: Map<number, string>,
): StormCard[] {
  return storms.map((storm) => ({
    pageKey: storm.pageKey,
    stale: storm.staleElementIds.map((id) => ({ id, name: nameById.get(id) ?? `#${id}` })),
    breaking: storm.aboutToBreakElementIds.map((id) => ({ id, name: nameById.get(id) ?? `#${id}` })),
  }))
}

/** Flat affected-entry rows for the batch trial-run suggestion card: one
 *  row per (page, element) carrying both signals. */
export interface TrialSuggestionRow {
  pageKey: string
  elementId: number
  elementName: string
  stale: boolean
  breaking: boolean
}

export function buildTrialSuggestions(cards: StormCard[]): TrialSuggestionRow[] {
  const rows: TrialSuggestionRow[] = []
  for (const card of cards) {
    const seen = new Map<number, TrialSuggestionRow>()
    for (const entry of card.stale) {
      seen.set(entry.id, {
        pageKey: card.pageKey,
        elementId: entry.id,
        elementName: entry.name,
        stale: true,
        breaking: false,
      })
    }
    for (const entry of card.breaking) {
      const existing = seen.get(entry.id)
      if (existing) existing.breaking = true
      else {
        seen.set(entry.id, {
          pageKey: card.pageKey,
          elementId: entry.id,
          elementName: entry.name,
          stale: false,
          breaking: true,
        })
      }
    }
    rows.push(...seen.values())
  }
  return rows
}

/** Project-slice self-heal reading, §8.4 batch caliber: the rate and the
 * rate next to a rising evidence count is the redesign signal). */
export interface SliceHealSummary {
  /** elements probed through the bounded evidence door */
  coveredElementCount: number
  /** elements with at least one self-heal evidence row */
  healedElementCount: number
  /** evidence rows seen across the probe (bounded pages only) */
  evidenceRowCount: number
  /** null when the denominator is unknown/zero — the card shows a dash */
  healRate: number | null
}

/** Aggregate the bounded per-element evidence pages collected by the page.
 *  `pages` maps elementId → that element's first (bounded) evidence page. */
export function summarizeSliceHeal(
  pages: Map<number, StepHealEvidenceRow[]>,
): SliceHealSummary {
  const coveredElementCount = pages.size
  let healedElementCount = 0
  let evidenceRowCount = 0
  for (const rows of pages.values()) {
    evidenceRowCount += rows.length
    if (rows.length > 0) healedElementCount += 1
  }
  return {
    coveredElementCount,
    healedElementCount,
    evidenceRowCount,
    healRate: coveredElementCount > 0 ? healedElementCount / coveredElementCount : null,
  }
}

/**
 * WE-S7 ledger health board contract (tick 9): the board derivations are
 * presentation-only, but the semantics they render are pinned here —
 * the badge color code table, the storm card's two-lists-never-pooled rule,
 * the trial-suggestion pairing, and the §8.4 bounded heal aggregation
 * (rate and evidence count read together, honest dash on empty input).
 */
import { describe, expect, it } from 'vitest'
import {
  buildStormCards,
  buildTrialSuggestions,
  healthTagType,
  summarizeSliceHeal,
} from '@/utils/webElementLedgerBoard'
import { formatHealRate } from '@/utils/webElementHealReport'
import type { RevisionStormRow, StepHealEvidenceRow } from '@/api/webElements'

function evidence(id: number, stepNo: number): StepHealEvidenceRow {
  return {
    id,
    taskId: 7,
    elementId: 100,
    stepNo,
    healed: true,
    firstAnchorFailed: true,
    fromAnchorType: 'css',
    fromAnchorValue: '.a',
    usedAnchorType: 'text',
    usedAnchorValue: '提交',
    viewport: '1280x720',
    ledgerRevision: 3,
    similarityScore: 0.9,
    createdAt: '2026-11-20T00:00:00Z',
  }
}

describe('ledger health board code table (pinned)', () => {
  it('maps every badge to exactly one el-tag color', () => {
    expect(healthTagType('green')).toBe('success')
    expect(healthTagType('yellow')).toBe('warning')
    expect(healthTagType('red')).toBe('danger')
    expect(healthTagType('none')).toBe('info')
  })
})

describe('storm cards', () => {
  it('keeps the stale and about-to-break lists side by side, never pooled', () => {
    const storm: RevisionStormRow = {
      pageKey: '/checkout',
      staleElementIds: [1, 2],
      aboutToBreakElementIds: [3],
    }
    const names = new Map<number, string>([
      [1, 'pay-btn'],
      [2, 'addr-input'],
      [3, 'coupon-link'],
    ])
    const [card] = buildStormCards([storm], names)
    expect(card.pageKey).toBe('/checkout')
    expect(card.stale).toEqual([
      { id: 1, name: 'pay-btn' },
      { id: 2, name: 'addr-input' },
    ])
    expect(card.breaking).toEqual([{ id: 3, name: 'coupon-link' }])
    // pooling violation: the two lists must stay separate containers
    expect(card.stale.some((e) => e.id === 3)).toBe(false)
    expect(card.breaking.length).toBe(1)
  })

  it('never drops an unknown id — it keeps an id label instead', () => {
    const [card] = buildStormCards(
      [{ pageKey: '/p', staleElementIds: [9], aboutToBreakElementIds: [] }],
      new Map(),
    )
    expect(card.stale).toEqual([{ id: 9, name: '#9' }])
  })

  it('trial suggestions fold both signals onto one row per element', () => {
    const names = new Map<number, string>([
      [1, 'a'],
      [2, 'b'],
      [3, 'c'],
    ])
    const cards = buildStormCards(
      [
        { pageKey: '/p1', staleElementIds: [1, 2], aboutToBreakElementIds: [1, 3] },
        { pageKey: '/p2', staleElementIds: [], aboutToBreakElementIds: [2] },
      ],
      names,
    )
    const rows = buildTrialSuggestions(cards)
    // /p1: element 1 carries both signals on one row; element 2 stale row.
    const p1e1 = rows.filter((r) => r.pageKey === '/p1' && r.elementId === 1)
    expect(p1e1).toHaveLength(1)
    expect(p1e1[0].stale).toBe(true)
    expect(p1e1[0].breaking).toBe(true)
    // /p2 keeps its own row for element 2 (page scope never merged)
    expect(rows.some((r) => r.pageKey === '/p2' && r.elementId === 2 && r.breaking)).toBe(true)
    expect(rows).toHaveLength(4)
  })
})

describe('slice heal aggregation (§8.4 batch caliber)', () => {
  it('reads rate and evidence count together over the bounded probe', () => {
    const pages = new Map<number, StepHealEvidenceRow[]>([
      [100, [evidence(1, 2), evidence(2, 5)]],
      [101, []],
      [102, [evidence(3, 1)]],
    ])
    const summary = summarizeSliceHeal(pages)
    expect(summary.coveredElementCount).toBe(3)
    expect(summary.healedElementCount).toBe(2)
    expect(summary.evidenceRowCount).toBe(3)
    expect(summary.healRate).toBeCloseTo(2 / 3)
    expect(formatHealRate(summary.healRate)).toBe('66.7%')
  })

  it('answers an honest dash when nothing was probed — never a fake 0%', () => {
    const summary = summarizeSliceHeal(new Map())
    expect(summary.healRate).toBeNull()
    expect(formatHealRate(summary.healRate)).toBe('-')
  })
})

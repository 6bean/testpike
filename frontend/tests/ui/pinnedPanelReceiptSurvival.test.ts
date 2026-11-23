/**
 * Pinned-panel receipt survival contract .
 *
 * Fails while the panel still answers `@applied` with a bare reload that
 * unmounts the door: the pure merge decides the outcome, and the section
 * must actually consume it (no second mount-loss path).
 */
import { describe, expect, it } from 'vitest'
import sectionRaw from '@/components/PinnedPendingGroups.vue?raw'
import { doorKey, mergePendingDoors } from '@/composables/pendingDoorMerge'

interface Door {
  projectId: number
  objectType: string
  cards: number[]
}

const door = (projectId: number, objectType: string, cards: number[]): Door => ({
  projectId,
  objectType,
  cards,
})

describe('pending door merge keeps answered receipts mounted', () => {
  it('an answered door the server no longer lists rides on with its key', () => {
    const answered = new Set([doorKey(door(7, 'SCRIPT', []))])
    const previous = [door(7, 'SCRIPT', [11]), door(7, 'CASE', [3])]
    const fresh = [door(7, 'CASE', [4])]
    const merged = mergePendingDoors(fresh, previous, answered, 7)
    // Same key present => the SAME instance survives and its receipt paints.
    expect(merged.map((d) => doorKey(d))).toEqual(['7:CASE', '7:SCRIPT'])
    expect(merged[1].cards).toEqual([11])
  })

  it('a door the server offers again takes the fresh row and loses its hold', () => {
    const answered = new Set([doorKey(door(7, 'SCRIPT', []))])
    const fresh = [door(7, 'SCRIPT', [21])]
    const merged = mergePendingDoors(fresh, [door(7, 'SCRIPT', [11])], answered, 7)
    expect(merged).toHaveLength(1)
    expect(merged[0].cards).toEqual([21])
    // The fresh row must be the ONLY instance of that key — no phantom
    // receipt door sitting beside the re-offered pending door.
    expect(merged.filter((d) => doorKey(d) === '7:SCRIPT')).toHaveLength(1)
  })

  it('retention never crosses the project slice (R30 single switch)', () => {
    const answered = new Set([doorKey(door(7, 'SCRIPT', []))])
    const merged = mergePendingDoors([], [door(7, 'SCRIPT', [11])], answered, 9)
    expect(merged).toEqual([])
  })

  it('unanswered doors the server dropped are removed (no phantom pending)', () => {
    const merged = mergePendingDoors([], [door(7, 'SCRIPT', [11])], new Set(), 7)
    expect(merged).toEqual([])
  })
})

describe('panel consumes the merge (wiring, not just the helper)', () => {
  it('applied answers route through the retainer before the re-read', () => {
    expect(sectionRaw).toContain('pendingDoorMerge')
    expect(sectionRaw).toContain('mergePendingDoors(')
    // The door must NOT be unmounted by a bare reload: the applied handler
    // records the key first, then re-reads.
    expect(sectionRaw).toMatch(/@applied="onDoorApplied\(/)
    expect(sectionRaw).toContain('answeredKeys.add(key)')
    // The header count counts pending rows only, never an answered receipt.
    expect(sectionRaw).toMatch(/answeredKeys\.has\(doorKey\(g\)\) \? 0 : g\.cards\.length/)
  })
})

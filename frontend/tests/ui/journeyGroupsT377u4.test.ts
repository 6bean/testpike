/**
 * segment c-4 U-4 journey-card editor (contract first).
 *
 * Defect this segment closes: step ② of the perf wizard edits a flat step
 * table only — the B-3 mix declaration (transaction/weight, both engines
 * now compile it) had no editing surface at all, and worse, the wizard's
 * save payload dropped the two keys, so a scenario whose mix was declared
 * elsewhere silently LOST the mix on any wizard edit (round-trip loss).
 *
 * Contract asserted here:
 * 1. deriveJourneyGroups mirrors PerfMixGroups.derive exactly: declaration
 * order, same-name merge, weight taken from the first declaration,
 * blank transaction skipped, flat scenario = empty list;
 * 2. journeySharePct mirrors the weight-fraction reading of
 * PerfMixGroups.linkRate (weight / sum; all-unweighted = even split) —
 * display-only, never a scheduling claim;
 * 3. enable/clear declaration helpers keep the whole-scenario invariant the
 * server save gate enforces (all steps in a link, or no mix keys at all;
 * no orphan weight can be produced client-side);
 * 4. stepForSave carries transaction/weight through the save payload AND
 * keeps the GA-1/ optional fields (auth/headers/body/saveAs/expect);
 * 5. the wizard wires the journey card zone (data-test anchors), caps
 * rendered cards for the 500-step worst scale, and the card zone is
 * chips + slider + donut — no el-table nested inside (U-4 red line);
 * 6. i18n dual-track: every journey key exists in both locales.
 */
import { describe, expect, it } from 'vitest'
import {
  JOURNEY_CARD_CAP,
  clearJourneyDeclaration,
  deriveJourneyGroups,
  enableJourneyDeclaration,
  journeySharePct,
  joinLastLink,
  stepForSave,
  type PerfStepRow,
} from '@/views/perf/journeyGroups'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

function row(patch: Partial<PerfStepRow>): PerfStepRow {
  return { action: 'http.request', method: 'GET', url: 'https://x/api/a', ...patch }
}

describe('T377 c-4 U-4 journey group derivation (server mirror)', () => {
  it('flat scenario derives zero links (pre-B-3 shape untouched)', () => {
    const links = deriveJourneyGroups([row({}), row({})])
    expect(links).toEqual([])
  })

  it('merges same-name steps in declaration order with first weight winning', () => {
    const links = deriveJourneyGroups([
      row({ transaction: '下单', weight: 3 }),
      row({ transaction: '浏览' }),
      row({ transaction: '下单', weight: 5 }),
    ])
    expect(links).toHaveLength(2)
    expect(links[0].name).toBe('下单')
    expect(links[0].weight).toBe(3)
    expect(links[0].indices).toEqual([0, 2])
    expect(links[1].name).toBe('浏览')
    expect(links[1].weight).toBe(0)
    expect(links[1].indices).toEqual([1])
  })

  it('blank transaction is skipped (not part of any link)', () => {
    const links = deriveJourneyGroups([row({ transaction: '  ' }), row({ transaction: 'A', weight: 2 })])
    expect(links).toHaveLength(1)
    expect(links[0].indices).toEqual([1])
  })

  it('share follows weight fractions; an all-unweighted set splits evenly', () => {
    const links = deriveJourneyGroups([
      row({ transaction: 'A', weight: 3 }),
      row({ transaction: 'B', weight: 1 }),
    ])
    expect(journeySharePct(links[0], links)).toBe(75)
    expect(journeySharePct(links[1], links)).toBe(25)
    const unweighted = deriveJourneyGroups([row({ transaction: 'A' }), row({ transaction: 'B' })])
    expect(journeySharePct(unweighted[0], unweighted)).toBe(50)
  })
})

describe('T377 c-4 U-4 declaration invariant helpers', () => {
  it('enable puts every step into a link so the all-or-none gate can hold', () => {
    const steps = [row({}), row({ transaction: '既有链路', weight: 4 })]
    enableJourneyDeclaration(steps, '主链路')
    expect(steps[0].transaction).toBe('主链路')
    expect(steps[0].weight).toBe(1)
    expect(steps[1].transaction).toBe('既有链路')
    expect(steps[1].weight).toBe(4)
    expect(deriveJourneyGroups(steps)).toHaveLength(2)
  })

  it('a grown step joins the last link at its weight (E2E-caught defect: added steps used to break the mix)', () => {
    const steps = [row({ transaction: '下单', weight: 4 })]
    steps.push(row({}))
    joinLastLink(steps)
    expect(steps[1].transaction).toBe('下单')
    expect(steps[1].weight).toBe(4)
    expect(deriveJourneyGroups(steps)[0].indices).toEqual([0, 1])
  })

  it('join is a no-op on a flat scenario and on an already-linked step', () => {
    const flat = [row({}), row({})]
    flat.push(row({}))
    joinLastLink(flat)
    expect(flat[2].transaction).toBeUndefined()
    const declared = [row({ transaction: 'A', weight: 2 })]
    declared.push(row({ transaction: 'B', weight: 3 }))
    joinLastLink(declared)
    expect(declared[1].transaction).toBe('B')
    expect(declared[1].weight).toBe(3)
  })

  it('clear strips BOTH keys from every step (never leaves an orphan weight)', () => {
    const steps = [row({ transaction: 'A', weight: 2 }), row({ weight: 7 })]
    clearJourneyDeclaration(steps)
    for (const s of steps) {
      expect(s.transaction).toBeUndefined()
      expect(s.weight).toBeUndefined()
    }
    expect(deriveJourneyGroups(steps)).toEqual([])
    expect(stepForSave(steps[0])).not.toHaveProperty('weight')
  })
})

describe('T377 c-4 U-4 save-payload round trip', () => {
  it('carries transaction/weight instead of silently dropping the mix', () => {
    const saved = stepForSave(row({ method: 'POST', transaction: '下单', weight: 3 }))
    expect(saved.transaction).toBe('下单')
    expect(saved.weight).toBe(3)
  })

  it('drops blank transaction and its weight together (gate invariant)', () => {
    const saved = stepForSave(row({ transaction: '   ', weight: 3 }))
    expect(saved).not.toHaveProperty('transaction')
    expect(saved).not.toHaveProperty('weight')
  })

  it('keeps the GA-1/T442 optional fields through the same serializer', () => {
    const saved = stepForSave(row({ auth: true, saveAs: 'tok', headers: { a: '1' } }))
    expect(saved.auth).toBe(true)
    expect(saved.saveAs).toBe('tok')
    expect(saved.headers).toEqual({ a: '1' })
  })
})

describe('T377 c-4 U-4 wizard wiring and scale guard', () => {
  it('step table renders journey cards with anchors and no nested el-table', () => {
    expect(wizardRaw).toContain('data-test="pw-journey-card"')
    expect(wizardRaw).toContain('data-test="pw-journey-weight"')
    expect(wizardRaw).toContain('data-test="pw-journey-enable"')
    expect(wizardRaw).toContain('data-test="pw-journey-clear"')
    // U-4 red line: cards + chips + slider + donut, never a table in a table.
    expect(wizardRaw).not.toContain('<el-table')
    // worst-scale guard: cards beyond the cap ride an honest fold, never full inline.
    expect(JOURNEY_CARD_CAP).toBeLessThanOrEqual(24)
    expect(wizardRaw).toContain('perf.wizard.journeyMoreLinks')
    // Vision close-out : the fold line is the zone's own
    // statement, separated from the card stack (dashed rule + lead-in), and
    // the zone title heads the group with more pitch than the card gap.
    expect(wizardRaw).toContain('pw__journey-fold')
    expect(wizardRaw).toMatch(/\.pw__journey-head \{[^}]*margin-bottom: 12px/)
    expect(wizardRaw).toMatch(/\.pw__journey-fold \{[^}]*border-top: 1px dashed/)
  })

  it('journey i18n keys exist in both locales (dual-track)', () => {
    for (const key of [
      'journeyEnable',
      'journeyDisable',
      'journeyTitle',
      'journeyWeight',
      'journeyShare',
      'journeyMembers',
      'journeyMoreMembers',
      'journeyMoreLinks',
      'journeyHint',
    ]) {
      expect(zhRaw, `zh-CN ${key}`).toContain(`${key}:`)
      expect(enRaw, `en-US ${key}`).toContain(`${key}:`)
    }
  })
})

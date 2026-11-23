/**
 * remaining face — message structure contract (adjudication §sixteen rule 5).
 *
 * Pinned rules:
 * - every turn carries its timestamp so the thread can draw a day divider:
 * live rows stamp Date.now at the call site, reload rows fold the ledger
 * created_at through the ONE mapper (no second folding copy);
 * - a divider renders only on the first row of each calendar day
 * (isFirstOfDay over the ordered turn list, pure function);
 * - role labels come from the locale single source (roleQuestion /
 * roleAnswer keys), never hard-coded text in the template;
 * - the two sides differ by construction: data-side = m.role drives the
 * right capsule (user) vs the left card (assistant) styling;
 * - F5 double-send guard: send flips the running flag synchronously
 * BEFORE awaiting the stream door, so a second Enter during the
 * session-creation in flight can never open a second run.
 */
import { describe, expect, it } from 'vitest'
import { mapHistoryToTurns } from '@/composables/chatHistoryMapper'
import { createAssistantTurn, createUserTurn, isFirstOfDay } from '@/composables/chatDockReducer'
import panelRaw from '@/components/ChatPanel.vue?raw'

function row(partial: Record<string, unknown> = {}) {
  return {
    id: 1, sessionId: 1, role: 'user', content: 'q', reasoning: null,
    status: 'done', seqFrom: null, seqTo: null, promptTokens: 0, completionTokens: 0,
    createdAt: '2026-10-28T09:30:00',
    ...partial,
  }
}

describe('createdAt folding (live and reload agree on the turn shape)', () => {
  it('a reloaded row keeps its ledger timestamp on the turn', () => {
    const turns = mapHistoryToTurns([row({ createdAt: '2026-10-28T09:30:00' })], 0)
    expect(Number.isNaN(turns[0].createdAt)).toBe(false)
    expect(turns[0].createdAt).toBe(new Date('2026-10-28T09:30:00').getTime())
  })

  it('a missing ledger timestamp degrades to now (never NaN hiding the row)', () => {
    const turns = mapHistoryToTurns([row({ createdAt: null })], 0)
    expect(Number.isNaN(turns[0].createdAt)).toBe(false)
  })

  it('the turn factories carry the stamp given at the call site', () => {
    const asked = createUserTurn(1, 'hello', 1234)
    expect(asked.createdAt).toBe(1234)
    const answered = createAssistantTurn(2, 5678)
    expect(answered.createdAt).toBe(5678)
  })

  it('the panel stamps fresh live turns with Date.now() at both call sites', () => {
    expect(panelRaw).toMatch(/createUserTurn\(\+\+rowSeq,\s*text,\s*Date\.now\(\)\)/)
    expect(panelRaw).toMatch(/createAssistantTurn\(\+\+rowSeq,\s*Date\.now\(\)\)/)
  })
})

describe('day divider placement (first row of each calendar day only)', () => {
  const day1 = new Date('2026-10-27T23:50:00').getTime()
  const day2a = new Date('2026-10-28T00:10:00').getTime()
  const day2b = new Date('2026-10-28T12:00:00').getTime()

  it('marks the first turn of a new day, never a same-day follow-up', () => {
    const list = [day1, day2a, day2b].map((t, i) => ({ id: i, createdAt: t }))
    expect(isFirstOfDay(list, 0)).toBe(true)
    expect(isFirstOfDay(list, 1)).toBe(true)
    expect(isFirstOfDay(list, 2)).toBe(false)
  })

  it('an empty list / out-of-range index still opens with a divider', () => {
    expect(isFirstOfDay([], 0)).toBe(true)
    expect(isFirstOfDay([{ id: 0, createdAt: day1 }], 5)).toBe(true)
  })

  it('the divider compares against the FULL ledger at the true index (window-safe)', () => {
    expect(panelRaw).toContain('chat-day-divider')
    expect(panelRaw).toMatch(/v-for="\(m, mi\) in visible"/)
    expect(panelRaw).toMatch(/isFirstOfDay\(turns,\s*winStart \+ mi\)/)
  })
})

describe('divider day label wording (T463 ledger debt closed with T464)', () => {
  it('the past-day label joins the numeric date and the weekday word with a space (mixed-script spacing, never glued)', () => {
    // The one formatter that jams both fields in a single Intl call is the
    // defect form (zh-CN renders the numeric date glued to the weekday word).
    // The fixed shape builds the two parts separately and joins them with an
    // explicit space, which reads right in every locale.
    expect(panelRaw).toMatch(/const datePart = new Intl\.DateTimeFormat\(locale\.value, \{ month: 'numeric', day: 'numeric' \}\)\.format\(ts\)/)
    expect(panelRaw).toMatch(/const weekdayPart = new Intl\.DateTimeFormat\(locale\.value, \{ weekday: 'short' \}\)\.format\(ts\)/)
    expect(panelRaw).toContain('return `${datePart} ${weekdayPart}`')
    // No single-call month+day+weekday formatter may come back.
    expect(panelRaw).not.toMatch(/month: 'numeric', day: 'numeric', weekday: 'short'/)
  })

  it('the yesterday bucket word rides the session-library locale key (one concept, one label)', () => {
    expect(panelRaw).toContain("groupYesterday")
  })
})

describe('panel wiring (structure single source)', () => {
  it('role labels ride the locale keys, no hard-coded wording in the template', () => {
    expect(panelRaw).toContain('roleQuestion')
    expect(panelRaw).toContain('roleAnswer')
  })

  it('side attributes exist for the E2E / vision probes', () => {
    expect(panelRaw).toMatch(/:data-side="m\.role"/)
  })

  it('F5: send flips running synchronously before awaiting the stream door', () => {
    const start = panelRaw.indexOf('async function send()')
    const body = panelRaw.slice(start, panelRaw.indexOf('/** Re-attach', start))
    expect(body).toContain('running.value = true')
    expect(body.indexOf('running.value = true'))
      .toBeLessThan(body.indexOf('await startStream'))
    // A refused door must restore the composer (running never sticks on).
    expect(body).toContain('catch')
  })
})

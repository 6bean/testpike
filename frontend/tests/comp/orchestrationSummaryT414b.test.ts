/**
 * b unit tests for the orchestration summary kernel —
 * the plain-language badges the flow view shows per row. The kernel must
 * (a) stay silent for absent/empty/malformed jsonb text (a bad row is
 * pointed out by the editor, not crash the rail), and (b) emit an i18n
 * key + params so the view never hard-codes display words.
 */
import { describe, expect, it } from 'vitest'

import {
  preconditionSummary,
  loopSummary,
} from '@/utils/orchestrationSummary'

describe('preconditionSummary', () => {
  it('returns null for absent / empty-object / malformed text', () => {
    expect(preconditionSummary(null)).toBeNull()
    expect(preconditionSummary(undefined)).toBeNull()
    expect(preconditionSummary('')).toBeNull()
    expect(preconditionSummary('{}')).toBeNull()
    expect(preconditionSummary('not json')).toBeNull()
  })

  it('status equality lands on the condition key with a readable expect list', () => {
    const s = preconditionSummary(JSON.stringify({ field: 'status', op: 'eq', expect: 200 }))
    expect(s).not.toBeNull()
    expect(s!.key).toBe('apitest.flow.preTip')
    expect(s!.params.cond).toContain('status')
    expect(s!.params.cond).toContain('200')
  })

  it('status in-list renders every code', () => {
    const s = preconditionSummary(JSON.stringify({ field: 'status', op: 'in', expect: [200, 201] }))
    expect(s!.params.cond).toContain('200')
    expect(s!.params.cond).toContain('201')
  })

  it('variable existence uses its own readable condition', () => {
    const s = preconditionSummary(JSON.stringify({ field: 'var', op: 'exists', name: 'orderId' }))
    expect(s!.params.cond).toContain('orderId')
    expect(s!.params.cond).toContain('exists')
  })
})

describe('loopSummary', () => {
  it('returns null for absent / empty-object / malformed text', () => {
    expect(loopSummary(null)).toBeNull()
    expect(loopSummary('{}')).toBeNull()
    expect(loopSummary('oops')).toBeNull()
  })

  it('count mode carries times and the delay folded into one phrasing', () => {
    const s = loopSummary(JSON.stringify({ mode: 'count', times: 5, delayMs: 1000 }))
    expect(s).not.toBeNull()
    expect(s!.key).toBe('apitest.flow.loopCountDelay')
    expect(s!.params.times).toBe('5')
    expect(s!.params.delay).toBe('1000ms')
  })

  it('count mode without delay renders a zero-wait phrasing', () => {
    const s = loopSummary(JSON.stringify({ mode: 'count', times: 3 }))
    expect(s!.params.delay).toBe('')
  })

  it('until mode names the stop condition and the safety ceiling', () => {
    const s = loopSummary(JSON.stringify({
      mode: 'until',
      maxTimes: 10,
      until: { field: 'status', op: 'eq', expect: 200 },
    }))
    expect(s!.key).toBe('apitest.flow.loopUntil')
    expect(s!.params.max).toBe('10')
    expect(s!.params.cond).toContain('status')
  })
})

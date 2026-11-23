/**
 * stage 3 — case-write proposal folding for the draft card (pure
 * logic, node vitest).
 *
 * A CASE_WRITE draft body is the case-field document the confirmation chain
 * sends to CaseService (title / precondition / expectedResult / priority /
 * confidence / endType / phase / steps). The card must show WHICH case fields
 * the proposal writes and, for an edit, WHAT CHANGES against the case as it
 * stands now — a human-readable fold, never a raw JSON dump (W-group).
 *
 * Honesty rules mirrored from chatRepairDiff:
 * - unparsable proposal JSON => null (the card shows "cannot compare",
 * never pretends an empty diff means "nothing changes");
 * - a proposal field that is absent/null writes nothing, so it never
 * appears in the diff;
 * - steps identity = (description, isAssert) per entry — a stepNo renumber
 * alone is noise and must not show as a change;
 * - readable step lines only when every entry carries a description,
 * otherwise the caller falls back to the count sentence.
 */
import { describe, expect, it } from 'vitest'
import {
  caseWriteFieldLines,
  diffCaseFields,
  proposalFieldOrder,
  stepSummary,
} from '@/composables/chatCaseWriteFold'

describe('proposalFieldOrder', () => {
  it('keeps the fixed vocabulary order and pushes unknown keys sorted at the end', () => {
    expect(proposalFieldOrder(['steps', 'title', 'weird', 'priority'])).toEqual([
      'title',
      'priority',
      'steps',
      'weird',
    ])
  })
})

describe('stepSummary', () => {
  it('counts steps and assertions of a parsable body', () => {
    const body = JSON.stringify({ steps: [{ description: 'a', isAssert: 0 }, { description: 'b', isAssert: 1 }] })
    expect(stepSummary(body)).toEqual({ steps: 2, asserts: 1 })
  })
  it('returns null without a steps array', () => {
    expect(stepSummary(JSON.stringify({ title: 'x' }))).toBeNull()
    expect(stepSummary('not json')).toBeNull()
  })
})

describe('caseWriteFieldLines', () => {
  it('folds the create proposal into readable field lines in vocabulary order', () => {
    const body = JSON.stringify({ title: '登录成功', priority: 2, steps: [{ description: '打开首页' }] })
    expect(caseWriteFieldLines(body)).toEqual([
      { field: 'title', value: '登录成功' },
      { field: 'priority', value: 2 },
    ])
  })
  it('skips null/absent fields and unparsable bodies', () => {
    expect(caseWriteFieldLines(JSON.stringify({ title: null, precondition: 'pre' }))).toEqual([
      { field: 'precondition', value: 'pre' },
    ])
    expect(caseWriteFieldLines('oops')).toBeNull()
  })
})

describe('diffCaseFields', () => {
  const current = {
    title: '旧标题',
    precondition: '旧前置',
    priority: 3,
    steps: [{ description: '第一步', isAssert: 0 }],
  }
  it('names changed fields and skips untouched ones', () => {
    const proposal = JSON.stringify({ title: '新标题', priority: 3 })
    expect(diffCaseFields(current, proposal)).toEqual([
      { field: 'title', state: 'changed', before: '旧标题', after: '新标题' },
    ])
  })
  it('step renumbering alone is not a change; content change is', () => {
    const renumber = JSON.stringify({ steps: [{ stepNo: 9, description: '第一步', isAssert: 0 }] })
    expect(diffCaseFields(current, renumber)).toEqual([])
    const edited = JSON.stringify({ steps: [{ description: '改过的第一步', isAssert: 1 }] })
    const changes = diffCaseFields(current, edited)
    expect(changes).toHaveLength(1)
    expect(changes[0].field).toBe('steps')
    expect(changes[0].state).toBe('changed')
  })
  it('a field the current case lacks shows as added', () => {
    const proposal = JSON.stringify({ expectedResult: '应返回令牌' })
    expect(diffCaseFields(current, proposal)).toEqual([
      { field: 'expectedResult', state: 'added', before: undefined, after: '应返回令牌' },
    ])
  })
  it('unparsable proposal or missing current case yields null (never a fake empty diff)', () => {
    expect(diffCaseFields(current, 'oops')).toBeNull()
    expect(diffCaseFields(null, JSON.stringify({ title: 'x' }))).toBeNull()
  })
})

/**
 * stage 3c — repair-draft field diff contract (pure logic, node vitest).
 *
 * The draft card must show which TOP-LEVEL action-JSON fields change (never a
 * raw JSON dump — W-group red line). This fold mirrors the version-line diff
 * shape (field / before / after) and stays honest about what it cannot do:
 * - unparsable proposal JSON => null (the card shows "cannot compare",
 * never an empty list pretending "nothing changes");
 * - missing current script (empty/null before) => diff against an empty
 * document: everything the draft adds shows as added;
 * - unchanged keys never appear; added/removed/changed are named per key.
 */
import { describe, expect, it } from 'vitest'
import { compactValue, diffActionJson } from '@/composables/chatRepairDiff'

describe('repair-draft field diff', () => {
  it('names added / removed / changed top-level keys, keeps equal keys out', () => {
    const before = JSON.stringify({ steps: [{ action: 'click' }], timeoutMs: 5000, keep: 1 })
    const after = JSON.stringify({ steps: [{ action: 'click' }], timeoutMs: 15000, added: true })
    const changes = diffActionJson(before, after)!
    expect(changes.map((c) => `${c.field}:${c.state}`)).toEqual(['added:added', 'keep:removed', 'timeoutMs:changed'])
    const changed = changes.find((c) => c.field === 'timeoutMs')!
    expect(changed.before).toBe(5000)
    expect(changed.after).toBe(15000)
  })

  it('no change at all yields an empty list (card says nothing changes)', () => {
    const json = '{"schemaVersion":"v2","steps":[]}'
    expect(diffActionJson(json, json)).toEqual([])
  })

  it('unparsable proposal returns null — never a fake empty diff', () => {
    expect(diffActionJson('{"steps":[]}', 'not-json')).toBeNull()
  })

  it('missing current script diffs against empty: everything reads as added', () => {
    expect(diffActionJson(null, '{"steps":[]}')).toEqual([
      { field: 'steps', state: 'added', before: undefined, after: [] },
    ])
  })

  it('corrupt current script with valid proposal returns null (cannot compare honestly)', () => {
    expect(diffActionJson('broken{', '{"steps":[]}')).toBeNull()
  })

  it('compactValue renders readable one-liners with a length cap', () => {
    // Structured values collapse through the caller's locale formatter — the
    // raw bracket shapes ([2]/[3]) were bookkeeping notation on screen
    // (vision T429s3c after-round), so the fold itself must stay wording-free.
    const shape = (kind: 'array' | 'object', n: number) => `${kind}:${n}`
    expect(compactValue(5000, shape)).toBe('5000')
    expect(compactValue([1, 2, 3], shape)).toBe('array:3')
    expect(compactValue({ a: 1, b: 2 }, shape)).toBe('object:2')
    expect(compactValue('x'.repeat(80), shape)).toBe(`${'x'.repeat(60)}…`)
    expect(compactValue(undefined, shape)).toBe('')
  })
})

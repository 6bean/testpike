import { describe, expect, it } from 'vitest'
import {
  jumpTargetForSource,
  resolveSpaceIdFromQuery,
  withMockJumpQuery,
} from '@/utils/mockJump'

/**
 * AG batch §12a seamless-jump contract pure helpers.
 * Cross-page jumps carry pre-filter context (projectId / spaceId / source) as
 * URL query keys; the target page consumes them honestly (never guesses a
 * value that is not in its loaded domain). This file is the single test home
 * for that logic — views only wire the helpers.
 */
describe('resolveSpaceIdFromQuery', () => {
  it('accepts a numeric space id that exists in the loaded list', () => {
    expect(resolveSpaceIdFromQuery('42', [7, 42, 99])).toBe(42)
  })

  it('accepts a number value directly (programmatic replace payloads)', () => {
    expect(resolveSpaceIdFromQuery(7, [7, 42])).toBe(7)
  })

  it('rejects an id outside the loaded domain instead of guessing', () => {
    expect(resolveSpaceIdFromQuery('123', [7, 42])).toBeNull()
  })

  it('rejects malformed values (non-numeric, zero, arrays)', () => {
    expect(resolveSpaceIdFromQuery('abc', [7])).toBeNull()
    expect(resolveSpaceIdFromQuery('0', [0, 7])).toBeNull()
    expect(resolveSpaceIdFromQuery(['7', '42'], [7, 42])).toBeNull()
    expect(resolveSpaceIdFromQuery(null, [7])).toBeNull()
    expect(resolveSpaceIdFromQuery(undefined, [7])).toBeNull()
  })

  it('returns null when no ids are loaded yet (deep link before data lands)', () => {
    expect(resolveSpaceIdFromQuery('42', [])).toBeNull()
  })
})

describe('withMockJumpQuery', () => {
  it('merges the three jump keys over the current query', () => {
    const out = withMockJumpQuery(
      { projectId: '3', tab: 'docs' },
      { projectId: 3, spaceId: 42, source: 'openapi' },
    )
    expect(out.query).toMatchObject({ projectId: 3, spaceId: 42, source: 'openapi', tab: 'docs' })
  })

  it('drops keys set to null (a cleared filter must not linger in the URL)', () => {
    const out = withMockJumpQuery(
      { projectId: '3', spaceId: '42', source: 'openapi' },
      { projectId: 3, spaceId: null, source: null },
    )
    expect(out.query).not.toHaveProperty('spaceId')
    expect(out.query).not.toHaveProperty('source')
    expect(out.query).toMatchObject({ projectId: 3 })
  })

  it('keeps unrelated query keys untouched (pre-filter rides, others survive)', () => {
    const out = withMockJumpQuery({ traceId: 't-1' }, { spaceId: 5 })
    expect(out.query).toMatchObject({ traceId: 't-1', spaceId: 5 })
  })
})

describe('jumpTargetForSource', () => {
  it('routes a case-flip rule back to the case detail when sourceRef is numeric', () => {
    const t = jumpTargetForSource({
      source: 'case-flip', sourceRef: '123', projectId: 3, spaceId: 42,
    })
    expect(t).not.toBeNull()
    expect(t!.path).toBe('/cases/123')
  })

  it('routes a case-flip rule without a usable ref back to the case list with the project key', () => {
    const t = jumpTargetForSource({
      source: 'case-flip', sourceRef: null, projectId: 3, spaceId: 42,
    })
    expect(t!.path).toBe('/cases')
    expect(t!.query).toMatchObject({ projectId: 3 })
  })

  it('routes an openapi rule back to the API-test page carrying the project key', () => {
    const t = jumpTargetForSource({
      source: 'openapi', sourceRef: 'spec:8', projectId: 3, spaceId: 42,
    })
    expect(t!.path).toBe('/api-test')
    expect(t!.query).toMatchObject({ projectId: 3 })
  })

  // AN-4: the traffic inspector merged into the per-space workbench —
  // a record/nl-ai rule lands on that space's inspector tab, and a rule
  // without a space id lands honestly on the spaces list (never guessed).
  it('routes record and nl-ai rules to the inspector tab of their space workbench', () => {
    const rec = jumpTargetForSource({ source: 'record', sourceRef: null, projectId: 3, spaceId: 42 })
    expect(rec!.path).toBe('/mock/spaces/42')
    expect(rec!.query).toMatchObject({ projectId: 3, tab: 'inspector' })
    const ai = jumpTargetForSource({ source: 'nl-ai', sourceRef: null, projectId: 3, spaceId: 42 })
    expect(ai!.path).toBe('/mock/spaces/42')
  })

  it('routes a record rule without a space id to the spaces list, projectId kept when present', () => {
    const rec = jumpTargetForSource({ source: 'record', sourceRef: null, projectId: 3, spaceId: null })
    expect(rec!.path).toBe('/mock/spaces')
    expect(rec!.query).toMatchObject({ projectId: 3 })
    const bare = jumpTargetForSource({ source: 'nl-ai', sourceRef: null, projectId: null, spaceId: null })
    expect(bare!.path).toBe('/mock/spaces')
    expect(bare!.query).toEqual({})
  })

  it('gives hand-written (manual) rules no return door', () => {
    expect(jumpTargetForSource({
      source: 'manual', sourceRef: null, projectId: 3, spaceId: 42,
    })).toBeNull()
    expect(jumpTargetForSource({
      source: null, sourceRef: null, projectId: 3, spaceId: 42,
    })).toBeNull()
  })

  it('stays silent on an unknown future source instead of inventing a route', () => {
    expect(jumpTargetForSource({
      source: 'telepathy', sourceRef: '9', projectId: 3, spaceId: 42,
    })).toBeNull()
  })
})

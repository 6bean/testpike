/**
 * tail — page-context whitelist contract (TC-AO-05 client door).
 *
 * The chat session may only carry navigation identifiers: route path, the
 * header-selected project, one object pointer, one execution id. Filters,
 * tabs and keyword searches on the same URL must NEVER ride into the
 * prompt (adjudication §context-whitelist; the core door re-validates).
 */
import { describe, expect, it } from 'vitest'
import { buildPageContext } from '@/composables/chatContextBuilder'

describe('buildPageContext whitelist', () => {
  it('carries route and the slice project for a plain page', () => {
    const ctx = JSON.parse(buildPageContext('/batch', {}, 42))
    expect(ctx.route).toBe('/batch')
    expect(ctx.projectId).toBe(42)
    expect(ctx.objectId).toBeUndefined()
    expect(ctx.executionId).toBeUndefined()
  })

  it('picks one object pointer from the query', () => {
    const ctx = JSON.parse(buildPageContext('/case', { caseId: '77' }, 42))
    expect(ctx.objectId).toBe('77')
  })

  it('picks the execution id when present', () => {
    const ctx = JSON.parse(buildPageContext('/execution', { executionId: 45 }, 42))
    expect(ctx.executionId).toBe(45)
  })

  it('never lets filter/tab/keyword noise into the context', () => {
    const raw = buildPageContext('/case', {
      tab: 'failed',
      keyword: 'password',
      status: '2',
      authorization: 'Bearer leak',
    }, 42)
    const ctx = JSON.parse(raw)
    expect(Object.keys(ctx).sort()).toEqual(['projectId', 'route'])
    expect(raw).not.toContain('password')
    expect(raw).not.toContain('Bearer')
  })

  it('only emits scalars — array-shaped query values are dropped', () => {
    const ctx = JSON.parse(buildPageContext('/case', { caseId: ['1', '2'] }, 42))
    expect(ctx.objectId).toBeUndefined()
  })
})

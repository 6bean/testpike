/**
 * Contract for the generated-result dead-link handoff.
 * Provenance row lives in docs/16; this file only pins behaviour.
 *
 * Evidence being defended: a case id that points at a cleaned sentinel used to
 * bounce the user to the case list with a toast that told them to look in the
 * recycle bin they had already checked (empty). The core now types the
 * rejection, and the frontend must:
 * - show that typed sentence on screen instead of firing it away with a
 * redirect (in-page error card, one click to the recoverable path);
 * - offer the recycle-bin door only when the delete record really exists;
 * - reach the case library through the existing segment channel (projectId
 * rides the deep link when known);
 * - gate the AI-generation completion door on the case actually opening, so a
 * dead id never produces a 404 round-trip.
 *
 * Pure logic + wiring assertions (node env per vitest.config.ts), the same
 * shape as caseRequirementEcho.test.ts.
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  caseLibraryRoute,
  classifyCaseLoadFailure,
  offersRecycleBinAction,
} from '@/utils/caseLoadFailure'

/** Verbatim sentences produced by the core typed not-found rejection. */
const DELETED_SENTENCE = '用例已被删除（id=933931）——可在「用例库」页的「回收站」中恢复后继续查看'
const SENTINEL_SENTENCE = '用例不存在（id=933931）——库中无此记录也无删除痕迹，请到「用例库」页按用例编号或标题搜索确认；若从 AI 生成结果跳转，请以「用例库」列表中的实际产出为准'

describe('classifyCaseLoadFailure reads the typed rejection', () => {
  it('types the soft-deleted rejection as deleted', () => {
    expect(classifyCaseLoadFailure(DELETED_SENTENCE)).toBe('deleted')
  })

  it('types the sentinel rejection as not-located', () => {
    expect(classifyCaseLoadFailure(SENTINEL_SENTENCE)).toBe('not-located')
  })

  it('falls back to generic for any other or empty message (no invented door)', () => {
    expect(classifyCaseLoadFailure('网络错误')).toBe('generic')
    expect(classifyCaseLoadFailure(undefined)).toBe('generic')
    expect(classifyCaseLoadFailure('')).toBe('generic')
  })
})

describe('offersRecycleBinAction keeps the recycle door honest', () => {
  it('offers the recycle bin only for a real delete record', () => {
    expect(offersRecycleBinAction('deleted')).toBe(true)
    expect(offersRecycleBinAction('not-located')).toBe(false)
    expect(offersRecycleBinAction('generic')).toBe(false)
  })
})

describe('caseLibraryRoute rides the existing case-library slice channel', () => {
  it('carries the project slice when known and the recycle flag only when asked', () => {
    expect(caseLibraryRoute(12, true)).toEqual({ path: '/cases', query: { projectId: '12', recycle: '1' } })
    expect(caseLibraryRoute(12, false)).toEqual({ path: '/cases', query: { projectId: '12' } })
  })

  it('omits an unknown project instead of writing an empty slice value', () => {
    expect(caseLibraryRoute(null, true)).toEqual({ path: '/cases', query: { recycle: '1' } })
    expect(caseLibraryRoute()).toEqual({ path: '/cases', query: {} })
  })
})

describe('views consume the single handoff helper', () => {
  const src = (rel: string) => readFileSync(resolve(__dirname, '../../src/views', rel), 'utf8')

  it('case detail renders an in-page error card instead of bouncing away', () => {
    const s = src('case/CaseDetailView.vue')
    expect(s).toContain('classifyCaseLoadFailure')
    expect(s).toContain('data-test="case-load-error"')
    expect(s).toContain('data-test="case-failure-open-recycle"')
    // the reported symptom: the typed guidance used to be flashed in a toast and
    // the route replaced immediately, leaving the user back at the list.
    expect(s).not.toMatch(/ElMessage\.error\(\(e as Error\)\.message \|\| t\('caseDetail\.loadFailed'\)\)\s*\n\s*router\.replace\('\/cases'\)/)
  })

  it('case list opens the recycle drawer from the deep link it points at', () => {
    const s = src('case/CaseListView.vue')
    expect(s).toMatch(/query\.recycle/)
    expect(s).toContain('recycleVisible')
  })

  it('generation completion gates the direct door on an opening case', () => {
    const s = src('ai/AiGenerateView.vue')
    expect(s).toContain('data-test="ai-gen-case-unreachable"')
    expect(s).toContain('probeGeneratedCase')
    expect(s).toMatch(/path: `\/cases\/\$\{task\.caseId\}`/)
  })
})

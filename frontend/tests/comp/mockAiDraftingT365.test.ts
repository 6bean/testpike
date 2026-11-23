/**
 * layout-contract mirror for the AI drafting surface
 * (?raw source-scan posture, same spectrum as mockHeaderLayoutT396).
 * Contract authorities:
 * - the admin bridge ships seven job doors (list/create/get/estimate/drafts/
 * confirm/ignore) — the frontend api/mock.ts must mirror all seven, zero fork;
 * - the drafting entry lives in MockDocsView header-actions as buttons only
 * (no selectors — screening/doing separation from the ruling);
 * - the create dialog echoes ownership (ProjectOwnerEcho, iron rule 1) and
 * the job-type picker is filterable (SE iron rule: selects must search);
 * - the confirmation drawer consumes the read-back door /mock/jobs/{id}/drafts
 * and confirm lands only ticked rows (empty selection is a local stop);
 * - i18n: the key family exists on both locale tracks, zero hard-coded CJK
 * in the new template region.
 * Replay note (concurrent-overwrite incident): restore this file to
 * frontend/tests/comp/mockAiDraftingT365.test.ts once the view wiring lands.
 */
import { describe, expect, it } from 'vitest'

import docsRaw from '@/views/mock/MockDocsView.vue?raw'
import mockApiRaw from '@/api/mock.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T365 AI drafting: api/mock.ts mirrors the seven job doors', () => {
  it.each([
    ['listAiJobs', /export function listAiJobs\b/],
    ['createAiJob', /export function createAiJob\b/],
    ['getAiJob', /export function getAiJob\b/],
    ['estimateAiJob', /export function estimateAiJob\b/],
    ['listAiJobDrafts', /export function listAiJobDrafts\b/],
    ['confirmAiJob', /export function confirmAiJob\b/],
    ['ignoreAiJob', /export function ignoreAiJob\b/],
  ])('exports %s against the /mock/jobs bridge', (_name, re) => {
    expect(mockApiRaw).toMatch(re)
    expect(mockApiRaw).toMatch(/\/mock\/jobs/)
  })

  it('mirrors the draft-item eight fields from the core read-back door', () => {
    for (const field of ['name', 'method', 'path', 'pathMatchType', 'responseJson', 'scenarioName', 'faultJson', 'sourceAnchor']) {
      expect(mockApiRaw).toContain(field)
    }
  })
})

describe('T365 AI drafting: MockDocsView wiring', () => {
  it('keeps the AI draft entry as a plain button (R23 stage-2: header-actions shell retired; the entry rides the filter-bar leading slot) with zero selectors in the action region', () => {
    // R23 stage-2: the title shell is gone, so pin the anchor instead of a
    // block region; the filter-bar row may screen (space picker) but the
    // action buttons themselves carry no selector.
    expect(docsRaw).toContain('mock-docs-ai-draft')
    expect(docsRaw).not.toMatch(/<div class="header-actions">/)
    expect(docsRaw).not.toMatch(/data-test="mock-docs-ai-draft"[\s\S]{0,40}?<el-select/)
    expect(docsRaw).not.toContain('ProjectSelect')
  })

  it('the job drawer entry is reachable even for read-only audiences', () => {
    expect(docsRaw).toContain('mock-docs-ai-jobs')
  })

  it('the create dialog hosts the ownership echo and a filterable job-type picker', () => {
    const dialog = /<el-dialog[^>]*mock-ai-dialog[\s\S]*?<\/el-dialog>/.exec(docsRaw)?.[0] ?? ''
    expect(dialog).toContain('ProjectOwnerEcho')
    expect(dialog).toMatch(/data-test="mock-ai-f-type"[^>]*filterable|filterable[^>]*data-test="mock-ai-f-type"/)
    expect(dialog).toContain('mock-ai-f-prompt')
  })

  it('the confirmation drawer reads drafts through listAiJobDrafts and confirms ticked rows', () => {
    expect(docsRaw).toContain('listAiJobDrafts')
    expect(docsRaw).toContain('confirmAiJob')
    expect(docsRaw).toContain('mock-ai-drawer-confirm')
    // an empty tick selection stops locally before hitting the server
    expect(docsRaw).toMatch(/aiNeedsSelection|mockAi\.needsSelection/)
  })

  it('ignore + estimate verbs ride the task drawer', () => {
    expect(docsRaw).toContain('ignoreAiJob')
    expect(docsRaw).toContain('estimateAiJob')
  })
})

describe('T365 AI drafting: i18n both tracks', () => {
  it('zh-CN carries the mockAi key family', () => {
    expect(zhRaw).toMatch(/\n\s*mockAi: \{/)
    for (const key of ['draftEntry', 'jobsEntry', 'dialogTitle', 'fJobType', 'fPrompt', 'needsSelection']) {
      expect(zhRaw.slice(zhRaw.indexOf('mockAi: {'))).toContain(`${key}:`)
    }
  })
  it('en-US carries the mockAi key family', () => {
    expect(enRaw).toMatch(/\n\s*mockAi: \{/)
    for (const key of ['draftEntry', 'jobsEntry', 'dialogTitle', 'fJobType', 'fPrompt', 'needsSelection']) {
      expect(enRaw.slice(enRaw.indexOf('mockAi: {'))).toContain(`${key}:`)
    }
  })
})

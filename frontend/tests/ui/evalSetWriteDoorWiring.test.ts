/**
 * AW2 write-door contract for the evaluation-set page (judge write-chain
 * facade batch). Posture = disk truth (same helper shape as
 * caseListActionBands): page/API/locale files are read from disk, no memory
 * lists. Assertions:
 *  1. the four page-level variant actions (manual add / adopted promotion /
 *     training-sample promotion / batch-result promotion) ride the
 *     .filter-bar-actions trailing slot in the create-first order, each
 *     gated by perm:judge:evalset:manage;
 *  2. the trailing slot carries no query-submit button (FB-2: Load samples
 *     stays inline at the end of the filter group);
 *  3. one single-source write dialog serves all four modes and its confirm
 *     button is disabled while nothing is added/selected (R28 dirty-state
 *     guard — an empty save must never reach the server as a fake success);
 *  4. the API mirror exposes publish / raise-from-adopted / raise /
 *     raise-from-batch with the exact admin-bridge wire shapes and zero
 *     self-reported operator id;
 *  5. every new locale key used by the page exists symmetrically in zh and
 *     en (i18n dual-track, no hard-coded Chinese on screen).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')

function read(rel: string): string {
  return readFileSync(join(SRC, rel), 'utf8')
}

function region(src: string, cls: string): string {
  const m = new RegExp(`<div[^>]*class="[^"]*\\b${cls}\\b[^"]*"[^>]*>`).exec(src)
  expect(m, `${cls} region must exist`).not.toBeNull()
  const start = m!.index + m![0].length
  let depth = 1
  let i = start
  const tok = /<div(?![\w-])|<\/div>/g
  tok.lastIndex = i
  let t: RegExpExecArray | null
  while (depth > 0 && (t = tok.exec(src)) !== null) {
    depth += t[0].startsWith('<div') ? 1 : -1
    i = t.index + t[0].length
  }
  return src.slice(start, i)
}

describe('eval-set page write doors (AW2, R21 band + R28 guard + i18n)', () => {
  const page = read('views/judge/EvalSetListView.vue')
  const api = read('api/evalSet.ts')
  const trailing = region(page, 'filter-bar-actions')

  it('trailing slot holds variant actions with the three promotions folded into one menu, create-first, refresh last', () => {
    const anchors = ['evalset-manual-btn', 'evalset-promote-btn', 'evalset-qagen-btn', 'evalset-refresh-btn']
    let last = -1
    for (const a of anchors) {
      const at = trailing.indexOf(a)
      expect(at, `trailing slot must carry ${a}`).toBeGreaterThan(last)
      last = at
    }
    // Vision V3 closure: the promotion channels stay reachable as menu items
    // of a single dropdown instead of three flat buttons.
    for (const item of ['evalset-adopted-item', 'evalset-training-item', 'evalset-batch-item']) {
      expect(page, `promote menu must carry ${item}`).toContain(item)
    }
  })

  it('every write action is gated by perm:judge:evalset:manage', () => {
    for (const a of ['evalset-manual-btn', 'evalset-promote-btn']) {
      const at = trailing.indexOf(a)
      const window = trailing.slice(Math.max(0, at - 400), at + 100)
      expect(window, `${a} needs the manage permission gate`).toContain("v-permission=\"'perm:judge:evalset:manage'\"")
    }
  })

  it('FB-2: no query-submit button inside the trailing slot', () => {
    expect(trailing).not.toContain('evalset-search-btn')
  })

  it('single write dialog serves four modes with a guarded confirm', () => {
    expect(page).toContain('evalset-write-dialog')
    for (const mode of ["'manual'", "'adopted'", "'training'", "'batch'"]) {
      expect(page, `dialog must handle mode ${mode}`).toContain(mode)
    }
    // R28: confirm is disabled while nothing was added / selected.
    const confirmAt = page.indexOf('evalset-write-confirm-btn')
    expect(confirmAt).toBeGreaterThan(-1)
    const btn = page.slice(Math.max(0, confirmAt - 500), confirmAt + 200)
    expect(btn).toMatch(/:disabled=/)
    expect(btn).toMatch(/:loading=/)
  })

  it('adopted mode reuses the single-source CaseSelect (no second picker)', () => {
    expect(page).toContain("import CaseSelect from '@/components/CaseSelect.vue'")
    expect(page).toContain('CaseSelect')
    expect(page).toContain('useProjectPageContext')
  })

  it('training mode loads candidates through the existing training-sample API', () => {
    expect(page).toContain('listTrainingSamples')
  })

  it('batch mode rides the single-source batch list and the narrowed task lane', () => {
    // Source data must come from the existing APIs (no second picker, no
    // dedicated promotion endpoint): listBatches for the batch dropdown and
    // listExecTasks narrowed to the failed/timeout terminal statuses.
    expect(page).toContain("import { listBatches } from '@/api/batch'")
    expect(page).toContain('listExecTasks')
    expect(page).toContain("statusIn: '4,5'")
    expect(page).toContain('evalset-batch-select')
    expect(page).toContain('evalset-batch-task-table')
    // Honest empty state: a batch without failed/timeout tasks must say so
    // on screen instead of offering an entry point core will reject.
    expect(page).toContain('judge.evalset.batchTasksEmpty')
  })

  it('API mirror exposes the four write doors with exact wire shapes', () => {
    expect(api).toContain("url: '/eval-sets/publish'")
    expect(api).toContain("url: '/eval-sets/raise-from-adopted'")
    expect(api).toContain("url: '/eval-sets/raise'")
    expect(api).toContain("url: '/eval-sets/raise-from-batch'")
    expect(api).toContain('export function raiseFromBatch')
    expect(api).toContain('export function publishEvalSet')
    expect(api).toContain('export function raiseFromAdoptedCases')
    expect(api).toContain('export function raiseFromTrainingSamples')
    // request payloads never carry an operator id (it is derived from the
    // login session server-side); createdBy may only appear as a read-only
    // field of the sample DTO above the write functions
    const writePart = api.slice(api.indexOf('export function publishEvalSet'))
    expect(writePart).not.toContain('createdBy')
  })

  it('i18n dual-track: write-door keys exist in zh and en symmetrically', () => {
    const keys = ['manualBtn', 'promoteMenu', 'versionCaption', 'adoptedBtn', 'trainingBtn', 'writeTitleManual', 'writeTitleAdopted',
      'writeTitleTraining', 'targetSetName', 'rowAddBtn', 'rowRemoveBtn', 'rowInput', 'rowExpected',
      'rowSensitive', 'confirmBtn', 'guardManual', 'guardSelection', 'publishSuccess', 'publishFailed',
      'manualCarryHint', 'trainingLoadFailed', 'trainingCandidatesHint', 'adoptedCasesHint',
      'batchBtn', 'writeTitleBatch', 'batchSelectLabel', 'batchSelectPlaceholder', 'batchTasksHint',
      'batchTasksEmpty', 'batchLoadFailed', 'batchTasksLoadFailed', 'taskColId', 'taskColStatus',
      'taskColError', 'statusFailed', 'statusTimeout', 'batchNoProject']
    const zh = read('utils/locales/zh-CN.ts')
    const en = read('utils/locales/en-US.ts')
    for (const k of keys) {
      const zhBlock = zh.slice(zh.indexOf('evalset: {'))
      const enBlock = en.slice(en.indexOf('evalset: {'))
      expect(zhBlock, `zh missing ${k}`).toContain(`${k}:`)
      expect(enBlock, `en missing ${k}`).toContain(`${k}:`)
    }
  })
})

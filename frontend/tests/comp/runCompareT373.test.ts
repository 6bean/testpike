/**
 * layout contract for the run-history response compare.
 *
 * Protected red lines:
 * - the compare vocabulary is core-owned; the front mirror must lock the
 * wire shape (mode JSON/TEXT/BINARY, kinds ADDED/REMOVED/TYPE_CHANGED/
 * VALUE_CHANGED) so a drift fails here instead of silently dropping values;
 * - exactly-two selection semantics (a third pick is gated, compare needs 2);
 * - tri-state rendering: JSON = four-color entry table, TEXT = line entries,
 * BINARY = digest-only honest note (never an empty diff disguised);
 * - truncated snapshots must stay labelled as partial coverage;
 * - the defect exit reuses the existing defect-library create chain —
 * no parallel defect system may appear in this drawer;
 * - dual-track i18n: zero hardcoded CJK, identical key sets in zh-CN/en-US.
 *
 * Posture = ?raw source mirror (same as runsetAttributionT372.test.ts).
 */
import { describe, expect, it } from 'vitest'

import apiRaw from '@/api/apiTest.ts?raw'
import drawerRaw from '@/views/apitest/RunSetDetailDrawer.vue?raw'
import zhLocale from '@/utils/locales/zh-CN'
import enLocale from '@/utils/locales/en-US'

const COMPARE_KEYS = [
  'comparePick',
  'compareBtn',
  'compareTitle',
  'compareIdentical',
  'compareBinaryNote',
  'compareSameDigest',
  'compareDiffDigest',
  'compareTruncatedWarn',
  'compareFail',
  'defectEntry',
  'defectDialogTitle',
  'defectTitlePh',
  'defectSevLabel',
  'defectDescLabel',
  'defectOk',
  'defectFail',
  'modeJson',
  'modeText',
  'modeBinary',
  'kindADDED',
  'kindREMOVED',
  'kindTYPE_CHANGED',
  'kindVALUE_CHANGED',
  'colPath',
  'colKind',
  'colLeft',
  'colRight',
]

describe('T373 compare: api mirror locks the core wire shape', () => {
  it('exposes compareRuns against the guarded admin bridge endpoint', () => {
    expect(apiRaw).toMatch(/export function compareRuns\(/)
    expect(apiRaw).toContain("'/apitest/runs/compare'")
    expect(apiRaw).toMatch(/method: 'get'/)
  })

  it('mirrors the mode and kind vocabularies literally (no second diff language)', () => {
    expect(apiRaw).toMatch(/type ResponseCompareMode = 'JSON' \| 'TEXT' \| 'BINARY'/)
    expect(apiRaw).toMatch(/type ResponseDiffKind = 'ADDED' \| 'REMOVED' \| 'TYPE_CHANGED' \| 'VALUE_CHANGED'/)
    expect(apiRaw).toMatch(/interface ResponseDiffEntry/)
    expect(apiRaw).toMatch(/interface ResponseCompareView/)
  })
})

describe('T373 compare: drawer selection + tri-state + defect exit', () => {
  it('gates the selection at two rows and requires exactly two to compare', () => {
    expect(drawerRaw).toMatch(/function toggleCompare\(/)
    // third pick while two are held must be refused inside the toggle helper
    const fn = drawerRaw.slice(drawerRaw.indexOf('function toggleCompare('))
    expect(fn.slice(0, 400)).toMatch(/\.length >= 2/)
    expect(drawerRaw).toContain('data-test="apitest-runset-compare-check"')
    expect(drawerRaw).toMatch(/data-test="apitest-runset-compare-btn"[^>]*:disabled="compareIds\.length !== 2"/s)
  })

  it('renders the three comparison faces distinctly', () => {
    expect(drawerRaw).toMatch(/compareView\.mode === 'BINARY'/)
    expect(drawerRaw).toMatch(/compareView\.mode === 'JSON'/)
    // BINARY face compares digests only and says so through i18n keys
    expect(drawerRaw).toContain('apitest.runSet.compareBinaryNote')
    expect(drawerRaw).toMatch(/compareView\.leftSha256 === compareView\.rightSha256/)
    // four-color kind column exists for the entry tables: the tag map holds
    // all four keys and each one has an explicit greppable i18n label
    for (const kind of ['ADDED', 'REMOVED', 'TYPE_CHANGED', 'VALUE_CHANGED']) {
      expect(drawerRaw, `kind ${kind} not classified in the tag map`).toMatch(new RegExp(`\\b${kind}:`))
      expect(drawerRaw).toContain(`apitest.runSet.kind${kind}`)
    }
  })

  it('labels truncated snapshots honestly instead of pretending full coverage', () => {
    expect(drawerRaw).toMatch(/leftTruncated \|\| .*rightTruncated|\.(leftTruncated|rightTruncated)/)
    expect(drawerRaw).toContain('apitest.runSet.compareTruncatedWarn')
  })

  it('routes the defect exit through the existing defect-library chain', () => {
    expect(drawerRaw).toMatch(/from '@\/api\/defectLibrary'/)
    expect(drawerRaw).toMatch(/createDefect\(/)
    expect(drawerRaw).toContain('data-test="apitest-runset-defect-btn"')
  })

  it('keeps zero hardcoded CJK in the new compare helpers', () => {
    const start = drawerRaw.indexOf('function toggleCompare(')
    expect(start).toBeGreaterThan(-1)
    const end = drawerRaw.indexOf('function setStatusLabel', start)
    const block = end > start ? drawerRaw.slice(start, end) : drawerRaw.slice(start, start + 1200)
    const cjk = block.match(/[\u4e00-\u9fff]/g)
    expect(cjk, `hardcoded CJK in compare block: ${cjk?.join('')}`).toBeNull()
  })
})

describe('T373 compare: locale dual-track parity', () => {
  const zh = (zhLocale as any).apitest.runSet
  const en = (enLocale as any).apitest.runSet

  it('carries every compare/defect key in both locales', () => {
    for (const key of COMPARE_KEYS) {
      expect(typeof zh[key], `zh-CN missing ${key}`).toBe('string')
      expect(typeof en[key], `en-US missing ${key}`).toBe('string')
    }
  })

  it('en-US values contain no CJK (translation completeness, not key presence only)', () => {
    for (const key of COMPARE_KEYS) {
      expect(en[key], `en-US ${key} still Chinese`).not.toMatch(/[\u4e00-\u9fff]/)
    }
  })
})

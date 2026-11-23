/**
 * Whole-site survey revisit contract (WU segment: R67 three doors).
 *
 * The survey-record list inside the profile drawer is the permanent
 * re-entry door: row action replays the stored process tail through the
 * SAME TaskTailDrawer the live tails use, a URL deep link (?surveyRun=)
 * opens it from any page, and an expired row answers with the three-part
 * pointer instead of a blank or a raw error. The cursor is forwarded per
 * run in a module-level buffer so leaving the page and coming back keeps
 * the frame sequence continuous — never a rewind to zero.
 */
import { describe, expect, it } from 'vitest'
import zh from '@/utils/locales/zh-CN'
import en from '@/utils/locales/en-US'
import { readTestSource } from './autoFollowWiringShared'
import { flatValues } from './localeShapeShared'

const drawerSrc = readTestSource('../../src/views/webelement/SiteProfileDrawer.vue')
const ledgerSrc = readTestSource('../../src/views/webelement/WebElementLedgerView.vue')
const apiSrc = readTestSource('../../src/api/siteProfiles.ts')
const replaySrc = readTestSource('../../src/composables/siteSurveyTailReplay.ts')

describe('survey-record list is wired into the profile drawer', () => {
  it('lists runs with a per-row replay door on the shared tail component', () => {
    expect(drawerSrc).toContain('data-test="runs-table"')
    expect(drawerSrc).toContain('data-test="run-detail-btn"')
    expect(drawerSrc).toContain('data-test="run-tail-dialog"')
    expect(drawerSrc).toContain('TaskTailDrawer')
  })
  it('profile rows dispatch a whole-site survey run in one click', () => {
    expect(drawerSrc).toContain('data-test="profile-survey-btn"')
    expect(drawerSrc).toContain('dispatchSiteSurveyRun')
  })
  it('deep-link door: the drawer accepts the run picked by the URL', () => {
    expect(drawerSrc).toContain('initialRunId')
  })
  it('cold-landing read door: opening is keyed on modelValue, never @open', () => {
    // el-drawer's @open never fires when the component mounts already open
    // (a deep link lands that way), so a drawer relying on it stays empty.
    expect(drawerSrc).not.toContain('@open="onOpen"')
    expect(drawerSrc).toMatch(/watch\(\(\)\s*=>\s*props\.modelValue/)
    expect(drawerSrc).toMatch(/if\s*\(props\.modelValue\)\s*void onOpen\(\)/)
  })
  it('replay reads through the ONE cursor door, no second event path', () => {
    expect(apiSrc).not.toContain('process-events')
    expect(replaySrc).toContain('fetchSurveyProcessEvents')
  })
})

describe('replay buffer keeps the cursor continuous across page visits', () => {
  it('buffers are keyed by run id in a module-level bounded cache', () => {
    expect(replaySrc).toContain('new Map<number, RunTailState>')
    expect(replaySrc).toContain('RUN_TAIL_CACHE_MAX')
  })
  it('loads forward from the last cursor and never rebinds events from zero', () => {
    expect(replaySrc).toMatch(/cursor\s*:\s*st\.cursor|st\.cursor\s*,/)
    expect(replaySrc).toContain('push(...')
  })
  it('follows a live run only while a viewer holds the dialog open', () => {
    expect(replaySrc).toContain('setInterval')
    expect(replaySrc).toContain('clearInterval')
  })
})

describe('ledger page carries the badge presence and the deep link', () => {
  it('reads ?surveyRun, opens the drawer onto that run', () => {
    expect(ledgerSrc).toContain('route.query.surveyRun')
    expect(ledgerSrc).toContain('initial-run-id')
  })
  it('closing strips the deep-link parameter and re-checks the badge', () => {
    expect(ledgerSrc).toContain('delete q.surveyRun')
  })
  it('a running whole-site survey keeps a live badge on the page', () => {
    expect(ledgerSrc).toContain('data-test="ledger-site-live"')
  })
})

describe('screen copy: status coverage, expiry pointer, no refresh instruction', () => {
  const zhBlock = (zh as Record<string, Record<string, unknown>>).siteProfile
  const enBlock = (en as Record<string, Record<string, unknown>>).siteProfile
  const NEED = [
    'runsTitle', 'runsEmpty', 'runColStatus', 'runColPages', 'runColLedger',
    'runColDegraded', 'runColStarted', 'runColActions', 'runColProfile',
    'statusRunning', 'statusCompleted', 'statusPartial', 'statusFailed', 'statusCancelled',
    'runDetail', 'surveyAction', 'dispatchOk', 'runTailTitle',
    'tailLoadMore', 'tailNoSession', 'siteLive',
  ]

  it('both locale faces carry every revisit key', () => {
    for (const k of NEED) {
      expect(zhBlock, `zh missing ${k}`).toHaveProperty(k)
      expect(enBlock, `en missing ${k}`).toHaveProperty(k)
    }
  })

  it('the expiry pointer lives on the shared drawer key only (single source)', () => {
    const runZh = (zh as Record<string, Record<string, unknown>>).runHistory
    const runEn = (en as Record<string, Record<string, unknown>>).runHistory
    expect(String(runZh.tailExpired)).toContain('{param}')
    expect(String(runZh.tailExpired)).toContain('参数设置')
    expect(String(runEn.tailExpired)).toContain('{param}')
    // host-private copies retired: the drawer renders the pointer itself
    expect(zhBlock).not.toHaveProperty('tailExpired')
    expect(enBlock).not.toHaveProperty('tailExpired')
  })

  it('revisit copy never instructs the user to refresh by hand', () => {
    for (const k of ['runsEmpty', 'tailNoSession', 'siteLive']) {
      expect(String(zhBlock[k])).not.toContain('刷新')
    }
    for (const v of flatValues(zhBlock)) {
      expect(v).not.toMatch(/人话|拒件|裁决|单源|棘轮|收口|出账/)
    }
  })
})

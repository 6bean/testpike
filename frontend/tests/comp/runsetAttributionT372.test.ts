/**
 * layout contract for the run-set honest attribution line.
 *
 * Red line being protected: a passed step must never read like "assertions
 * passed" when the request never had assertions configured (ws rows that only
 * connected, or legacy rows with an empty assert_detail). The attribution
 * vocabulary is single-sourced in RunSetDetailDrawer.stepAttribution and the
 * i18n families — no literal Chinese may be baked into the component.
 *
 * Posture = the ?raw source mirror used by runsetT369.test.ts: assertions read
 * the shipped template/script text so a regression fails long before render.
 *
 * Clauses:
 * 1. the attribution helper exists and branches on protocol + assertCount;
 * 2. every rendered string goes through t('apitest.runSet.stepAttr...') —
 * zero hardcoded CJK inside the helper (dual-track i18n iron rule);
 * 3. the anchor span is data-test-tagged and only rendered when non-empty;
 * 4. all four vocabulary keys exist in both locale files with identical
 * key sets (zh-CN / en-US dual-track parity).
 */
import { describe, expect, it } from 'vitest'

import drawerRaw from '@/views/apitest/RunSetDetailDrawer.vue?raw'
import zhLocale from '@/utils/locales/zh-CN'
import enLocale from '@/utils/locales/en-US'

/** segment the stepAttribution function body out of the setup script. */
function attributionFn(src: string): string {
  const start = src.indexOf('function stepAttribution(')
  expect(start, 'stepAttribution helper missing from the drawer').toBeGreaterThan(-1)
  const end = src.indexOf('\n}', start)
  expect(end, 'stepAttribution never closes').toBeGreaterThan(start)
  return src.slice(start, end)
}

const fn = attributionFn(drawerRaw)

describe('T372 runset attribution: single-source honest vocabulary', () => {
  it('branches on protocol and assertCount (the two truth inputs)', () => {
    expect(fn).toMatch(/s\.protocol === 'ws'/)
    expect(fn).toMatch(/s\.assertCount > 0/)
    // Only passed rows carry an attribution line; other statuses already tell
    // the truth through the status tag.
    expect(fn).toMatch(/s\.status !== 1/)
  })

  it('renders through i18n keys only — zero hardcoded CJK in the helper', () => {
    const cjk = fn.match(/[\u4e00-\u9fff]/g)
    expect(cjk, `hardcoded CJK in attribution helper: ${cjk?.join('')}`).toBeNull()
    for (const key of [
      'stepAttrWsPassedWithAsserts',
      'stepAttrWsConnected',
      'stepAttrHttpPassed',
      'stepAttrHttpNoAssert',
    ]) {
      expect(fn, `vocabulary key ${key} not consumed`).toContain(`apitest.runSet.${key}`)
    }
  })

  it('mounts the attribution span behind a non-empty guard with a data-test anchor', () => {
    expect(drawerRaw).toMatch(/v-if="stepAttribution\(s\)"[^>]*data-test="apitest-runset-attribution"/)
  })
})

describe('T372 runset attribution: locale dual-track parity', () => {
  const zh = (zhLocale as any).apitest.runSet
  const en = (enLocale as any).apitest.runSet

  it('carries all four attribution keys in both locales', () => {
    for (const key of [
      'stepAttrHttpPassed',
      'stepAttrHttpNoAssert',
      'stepAttrWsPassedWithAsserts',
      'stepAttrWsConnected',
    ]) {
      expect(typeof zh[key], `zh-CN missing ${key}`).toBe('string')
      expect(typeof en[key], `en-US missing ${key}`).toBe('string')
    }
  })

  it('ws zero-assertion line says connected-no-assertions in both languages', () => {
    expect(zh.stepAttrWsConnected).toContain('未配断言')
    expect(en.stepAttrWsConnected).toMatch(/no assertions/i)
  })
})

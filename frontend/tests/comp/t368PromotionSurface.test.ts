/**
 * layout contract: the promotion surface must exist end-to-end
 * on the user side (source-mirror posture of ownerEchoWiringT396b.test.ts —
 * assertions read the SFC/TS sources as text, no mounting).
 *
 * What the promotion chain owes the UI (ruling R-10 + W-c1):
 * 1. the API-test sandbox page carries TWO distinct destinations — a real
 * "promote into regression case" action hitting the admin promote route,
 * and the existing save-as-perf-scenario jump (whose copy no longer says
 * "promotion" to avoid the ambiguity);
 * 2. a promoted request shows a badge that is click-reachable to the created
 * case detail (double-direction lineage, no dead ends);
 * 3. the case detail side names its sandbox origin (source=6 + sourceRef
 * "apitest:{id}" rendered as a jumpable badge);
 * 4. the source dictionary labels value 6 in both locales (i18n dual-track,
 * zero hard-coded Chinese in components).
 */
import { describe, expect, it } from 'vitest'

import apiTestRaw from '@/api/apiTest.ts?raw'
import caseApiRaw from '@/api/case.ts?raw'
import apitestViewRaw from '@/views/apitest/ApiTestView.vue?raw'
import caseDetailRaw from '@/views/case/CaseDetailView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T368 promotion surface (sandbox -> regression case)', () => {
  it('api layer mirrors the admin promote route', () => {
    expect(apiTestRaw).toContain('export function promoteRequest')
    expect(apiTestRaw).toContain('/apitest/requests/')
    expect(apiTestRaw).toContain('/promote')
    expect(apiTestRaw).toContain('PromotionResult')
  })

  it('sandbox page carries the promote action wired to the api layer', () => {
    expect(apitestViewRaw).toContain('promoteRequest')
    expect(apitestViewRaw).toContain('apitest-promote-btn')
    // the perf jump keeps its own anchor (regression guard: splitting the
    // entry must not delete the existing destination)
    expect(apitestViewRaw).toContain('apitest-save-as-perf-btn')
  })

  it('promoted badge exists and is click-reachable to the case detail', () => {
    expect(apitestViewRaw).toContain('apitest-promoted-badge')
    expect(apitestViewRaw).toMatch(/\/cases\//)
  })

  it('case detail names the sandbox origin as a jumpable badge', () => {
    expect(caseDetailRaw).toContain('case-promoted-origin')
    expect(caseDetailRaw).toContain('apitest:')
    expect(caseDetailRaw).toContain('/api-test')
  })

  it('source dictionary covers value 6 (sandbox promotion)', () => {
    expect(caseApiRaw).toMatch(/get 6\(\)/)
    expect(zhRaw).toContain('sandboxPromotion')
    expect(enRaw).toContain('sandboxPromotion')
  })

  it('case detail labels an api-target case honestly (no web mislabel)', () => {
    // Promoted cases carry automation_target=api; the view chip must name the
    // API surface instead of falling through to the web branch.
    expect(caseDetailRaw).toMatch(/target === 'api'/)
    expect(zhRaw).toContain('targetApi')
    expect(enRaw).toContain('targetApi')
  })

  it('promote copy lives in both locale tracks (no hard-coded strings)', () => {
    for (const key of ['promoteToCase', 'promotedTag', 'promoteOk', 'promoteFail']) {
      expect(zhRaw.slice(zhRaw.indexOf('apitest:'))).toContain(key)
      expect(enRaw.slice(enRaw.indexOf('apitest:'))).toContain(key)
    }
  })
})

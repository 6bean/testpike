/**
 * layout contract for the R-4 AI mock switch-family card.
 * Contract authorities: the feature plan R-4 ruling (ai.mock.enabled ∧ the
 * per-application-point sub-switches live on the system-config page as one
 * card, every switch carrying the three-part plain-language sentence —
 * what it does / how much token it burns / degrade behaviour when off) and
 * the in-page repair-gate-card precedent shape (M8). A missing key means the
 * backend migration has not landed, so the card hides instead of faking state.
 */
import { describe, expect, it } from 'vitest'

import configRaw from '@/views/system/ConfigListView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

const GATE_KEYS = [
  'ai.mock.enabled',
  'ai.mock.nl-stub',
  'ai.mock.edge-scenarios',
  'ai.mock.spec-sync',
]

describe('T365 R-4: ConfigListView ships the AI mock switch-family card', () => {
  it('renders one gated card with a stable data-test anchor', () => {
    expect(configRaw).toContain('ai-mock-gate-card')
    // hide-when-migrated-missing posture, mirroring the repair card
    expect(configRaw).toMatch(/v-if="aiMockMasterCfg/)
  })

  it.each([
    ['master', 'ai.mock.enabled', 'ai-mock-gate-master'],
    ['nl-stub', 'ai.mock.nl-stub', 'ai-mock-gate-nl-stub'],
    ['edge-scenarios', 'ai.mock.edge-scenarios', 'ai-mock-gate-edge'],
    ['spec-sync', 'ai.mock.spec-sync', 'ai-mock-gate-spec-sync'],
  ])('wires the %s switch against key %s', (_name, key, testId) => {
    expect(configRaw).toContain(key)
    expect(configRaw).toContain(testId)
  })

  it('reuses the single gate-write-back piece instead of forking a saver', () => {
    // saveGateCfg already exists (red line 6); the new card must consume it
    const saves = configRaw.match(/saveGateCfg\(/g) ?? []
    expect(saves.length).toBeGreaterThanOrEqual(5)
  })

  it('carries three-part hints per switch (does / token / degrade-when-off)', () => {
    for (const suffix of ['Do', 'Token', 'Off']) {
      for (const base of ['aiMockMaster', 'aiMockNl', 'aiMockEdge', 'aiMockSpec']) {
        expect(configRaw).toContain(`config.${base}${suffix}`)
      }
    }
  })
})

describe('T365 R-4: locale tracks mirror the switch-family key family', () => {
  it.each(GATE_KEYS)('backend key %s is referenced on the vue side', (key) => {
    expect(configRaw).toContain(key)
  })

  it('zh and en both define the full aiMock key family (dual-track, zero hard CJK)', () => {
    for (const base of ['aiMockMaster', 'aiMockNl', 'aiMockEdge', 'aiMockSpec']) {
      for (const suffix of ['Label', 'Do', 'Token', 'Off']) {
        const re = new RegExp(`${base}${suffix}:\\s*['"]`)
        expect(zhRaw).toMatch(re)
        expect(enRaw).toMatch(re)
      }
    }
    expect(zhRaw).toMatch(/aiMockGateTitle:\s*['"]/)
    expect(enRaw).toMatch(/aiMockGateTitle:\s*['"]/)
  })

  it('the new card template region carries zero hard-coded CJK labels', () => {
    const card = /data-test="ai-mock-gate-card"[\s\S]*?<\/div>\s*<\/div>/.exec(configRaw)?.[0] ?? ''
    expect(card).not.toBe('')
    // only t(...) calls render text inside the card
    expect(card).toMatch(/t\('config\.aiMock/)
    const literals = card.match(/>[^<>{}]*[\u4e00-\u9fff][^<>{}]*<\//g) ?? []
    expect(literals).toEqual([])
  })
})

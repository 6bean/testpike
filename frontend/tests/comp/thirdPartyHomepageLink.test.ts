/**
 * third-party homepage quick-link contract (Vitest Browser Mode, real Chromium).
 * - Component behavior: a non-empty URL renders a new-tab anchor with
 * rel="noopener noreferrer" and a stable data-test; an empty/null URL renders
 * an honest grey dash and NEVER an anchor (no dead links).
 * - Single-source reuse: UpgradeCenterView panorama table and EngineCenterView
 * install-doc link both consume ThirdPartyLinkCell — no second inline anchor
 * (project red-line: shared logic reused twice must not fork).
 * - API mirror: ClusterComponentRow carries homepageUrl: string | null (admin
 * bridge / core ThirdPartyHomepageRules already proven at L3).
 * - i18n mirror: upgrade.colHomepage + common.openHomepage exist in both zh-CN
 * and en-US (zero hardcoded Chinese in templates).
 */
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

import ThirdPartyLinkCell from '@/components/ThirdPartyLinkCell.vue'
import clusterApiRaw from '@/api/cluster?raw'
import upgradeViewRaw from '@/views/cluster/UpgradeCenterView.vue?raw'
import engineViewRaw from '@/views/appauto/EngineCenterView.vue?raw'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: { 'zh-CN': zhCN, 'en-US': enUS },
})

function factory(props: Record<string, unknown>) {
  return mount(ThirdPartyLinkCell, {
    props,
    global: { plugins: [ElementPlus, i18n] },
  })
}

describe('ThirdPartyLinkCell', () => {
  it('renders a new-tab anchor with safe rel and href when url present', () => {
    const w = factory({ url: 'https://nodejs.org', label: 'Node', dataTest: 'tp-link-node' })
    const a = w.find('a[data-test="tp-link-node"]')
    expect(a.exists()).toBe(true)
    expect(a.attributes('href')).toBe('https://nodejs.org')
    expect(a.attributes('target')).toBe('_blank')
    expect(a.attributes('rel')).toContain('noopener')
    expect(a.attributes('rel')).toContain('noreferrer')
  })

  it('renders an honest dash and no anchor when url is empty or null', () => {
    for (const url of [null, '', undefined]) {
      const w = factory({ url, label: 'executor-agent' })
      expect(w.find('a').exists()).toBe(false)
      expect(w.text()).toContain('—')
    }
  })

  it('keeps the icon-only form for table cells (label optional)', () => {
    const w = factory({ url: 'https://appium.io', dataTest: 'tp-icon-cell' })
    expect(w.find('a[data-test="tp-icon-cell"]').exists()).toBe(true)
    // text-only expectation would be wrong here — icon anchor carries no label text
    expect(w.find('a[data-test="tp-icon-cell"]').text()).toBe('')
  })
})

describe('single-source reuse mirrors', () => {
  it('cluster.ts ClusterComponentRow mirrors homepageUrl as nullable', () => {
    expect(clusterApiRaw).toMatch(/homepageUrl:\s*string\s*\|\s*null/)
  })

  it('UpgradeCenterView panorama table wires the shared cell + i18n column key', () => {
    expect(upgradeViewRaw).toContain('ThirdPartyLinkCell')
    expect(upgradeViewRaw).toContain("t('upgrade.colHomepage')")
    expect(upgradeViewRaw).toContain('row.homepageUrl')
  })

  it('EngineCenterView reuses the shared cell instead of an inline anchor', () => {
    expect(engineViewRaw).toContain('ThirdPartyLinkCell')
    // the previous inline <a ... installDocUrl ... target="_blank"> must be gone
    expect(engineViewRaw).not.toMatch(/<a[^>]*installDocUrl/)
  })
})

describe('i18n dual-track keys', () => {
  it.each([['zh-CN', zhCN], ['en-US', enUS]])('%s carries colHomepage + openHomepage', (_loc, msgs: any) => {
    expect(msgs.upgrade.colHomepage).toBeTruthy()
    expect(msgs.common.openHomepage).toBeTruthy()
  })
})

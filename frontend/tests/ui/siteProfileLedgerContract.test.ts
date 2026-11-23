/**
 * Site profile ledger contract (whole-site survey profile segment): the
 * browser door mirrors the admin REST paths and record component names one
 * to one, both locale faces carry the identical key set with no internal
 * governance wording on the value face, the screen shows the mapped
 * vocabulary from the adjudication table, and the ledger page wires the
 * entry button plus the drawer mount with the empty-save guard in place.
 */
import { describe, expect, it } from 'vitest'
import zh from '@/utils/locales/zh-CN'
import en from '@/utils/locales/en-US'
import { readTestSource } from './autoFollowWiringShared'
import { flatKeys, flatValues } from './localeShapeShared'

const apiSrc = readTestSource('../../src/api/siteProfiles.ts')
const drawerSrc = readTestSource('../../src/views/webelement/SiteProfileDrawer.vue')
const ledgerSrc = readTestSource('../../src/views/webelement/WebElementLedgerView.vue')

describe('site profile api mirrors the admin doors one to one', () => {
  it('lists by projectId on the collection path and reads a row by id', () => {
    expect(apiSrc).toMatch(/url: '\/site-profiles'/)
    expect(apiSrc).toMatch(/method: 'get'[\s\S]*?params: \{ projectId \}/)
    expect(apiSrc).toMatch(/url: `\/site-profiles\/\$\{id\}`/)
  })
  it('carries the three write verbs the drawer uses', () => {
    expect(apiSrc).toMatch(/method: 'post'/)
    expect(apiSrc).toMatch(/method: 'put'/)
    expect(apiSrc).toMatch(/method: 'delete'/)
  })
  it('field names follow the admin record components verbatim', () => {
    for (const field of [
      'scopePrefix', 'excludeGlobs', 'ignoreParams', 'verbsGuardEnabled',
      'budgetOverrides', 'loginStateName', 'revision', 'updatedAt',
    ]) {
      expect(apiSrc).toContain(field)
    }
  })
})

describe('locale faces stay symmetric and user-facing', () => {
  const zhBlock = (zh as Record<string, Record<string, unknown>>).siteProfile
  const enBlock = (en as Record<string, Record<string, unknown>>).siteProfile

  it('zh and en carry the identical siteProfile key set', () => {
    expect(zhBlock).toBeTruthy()
    expect(flatKeys(zhBlock)).toEqual(flatKeys(enBlock))
  })

  it('screens show the mapped vocabulary from the adjudication table', () => {
    const sp = zhBlock as Record<string, string>
    expect(sp.colScope).toBe('站点范围')
    expect(sp.entry).toBe('站点档案')
    expect(sp.drawerTitle).toBe('站点档案')
    expect(sp.budgetPerTemplateCap).toBe('同类页面采集上限')
    expect(sp.deleteConfirm).toContain('历史测绘记录与已入账元素保留')
    expect(sp.deletedOk).toContain('历史测绘记录与已入账元素保留')
  })

  it('no internal governance wording reaches the value face', () => {
    const banned = /(人话|拒件|裁决|单源|棘轮|落章|出账|收口|plain-language|plain language|single source|adjudicat)/
    for (const v of [...flatValues(zhBlock), ...flatValues(enBlock)]) {
      expect(banned.test(v)).toBe(false)
    }
  })

  it('no manual-refresh instruction sentence reaches the value face', () => {
    const bannedZh = /(直接刷新|请刷新|刷新后重试|刷新页面|稍后刷新|稍后再刷新|手动刷新|稍后重开|重开查看|点击「刷新」|点击『刷新』)/
    const bannedEn = /(refresh later|refresh the page|click (the )?'?refresh\b|press refresh)/i
    for (const v of flatValues(zhBlock)) expect(bannedZh.test(v)).toBe(false)
    for (const v of flatValues(enBlock)) expect(bannedEn.test(v)).toBe(false)
  })
})

describe('ledger page wires the profile drawer', () => {
  it('entry button carries the test hook and opens the mounted drawer', () => {
    expect(ledgerSrc).toContain('data-test="ledger-profile-btn"')
    expect(ledgerSrc).toContain('<SiteProfileDrawer')
    expect(ledgerSrc).toContain('profileDrawerVisible')
  })
  it('drawer keeps the create door project-gated and the save door change-gated', () => {
    expect(drawerSrc).toContain(':disabled="!projectId"')
    expect(drawerSrc).toContain('data-test="profile-save-btn"')
    expect(drawerSrc).toMatch(/:disabled="!dirty"/)
    expect(drawerSrc).toContain('t(\'siteProfile.deleteConfirm\'')
  })
})

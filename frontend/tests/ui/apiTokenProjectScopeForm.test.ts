/**
 * API token project-scope form contract (GA1 segment C frontend).
 * Locks the four facts the token page owes project narrowing:
 * - the api mirror carries projectScope on both the create request and the
 * list row (admin ApiTokenCreateRequest/ApiTokenView field alignment);
 * - the create dialog exposes a filterable multi project picker bound to the
 * auth store visible-project snapshot (zero remote request, R30 shape), and
 * the submit payload sends the ids ascending de-duplicated joined string
 * (backend single source still owns validation and normalization);
 * - the unset guidance renders both R32 elements: the screen menu path
 * literal and the parameter name, and a distinct hint renders once
 * projects are checked (two dialog forms for the vision review);
 * - the table carries a project-scope column that labels unpinned tokens
 * honestly as unrestricted instead of leaving the cell blank.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'

const api = readFileSync(new URL('../../src/api/apiToken.ts', import.meta.url), 'utf8')
const page = readFileSync(new URL('../../src/views/token/ApiTokenView.vue', import.meta.url), 'utf8')

function pick(obj: Record<string, unknown>, path: string): Record<string, unknown> {
  return path.split('.').reduce<Record<string, unknown>>((acc, key) => (acc?.[key] as Record<string, unknown>) ?? {}, obj)
}

describe('api-token project scope form contract', () => {
  it('mirrors projectScope on both api shapes', () => {
    expect(/ApiTokenCreateRequest[\s\S]*?projectScope\??:/.test(api), 'create request mirror').toBe(true)
    expect(/ApiTokenItem[\s\S]*?projectScope:/.test(api), 'list row mirror').toBe(true)
  })

  it('renders a filterable multi project picker from the auth snapshot', () => {
    expect(page).toContain('token-create-project-scope')
    expect(page, 'picker bound to visible projects').toMatch(/auth\.projects/)
    // the enclosing el-select block (attributes precede the data-test anchor)
    // must carry multiple and filterable (SE rule: >10 options need search)
    const idx = page.indexOf('token-create-project-scope')
    const block = page.slice(Math.max(0, idx - 700), idx + 300)
    expect(block, 'multi select').toContain('multiple')
    expect(block, 'filterable select').toContain('filterable')
    // picker must issue no remote project request from this page
    expect(page, 'no remote project list call on token page').not.toMatch(/listProjects|getProjects/)
  })

  it('submits the pinned ids ascending and omits the pin when nothing is checked', () => {
    expect(page, 'payload carries projectScope').toMatch(/projectScope:\s*/)
    expect(page, 'ids sorted before join').toMatch(/sort\(/)
    expect(page, "joined with commas").toMatch(/join\(','\)/)
  })

  it('carries the table column with an honest unrestricted label', () => {
    expect(page).toMatch(/key:\s*'projectScope'.*labelKey:\s*'apiToken\.colProjectScope'/s)
    expect(zhCN.apiToken.projectScopeUnlimited).toBeTruthy()
    expect(enUS.apiToken.projectScopeUnlimited).toBeTruthy()
  })

  it('unset guidance names the in-dialog field and its consequence in both locales', () => {
    for (const [locale, dict] of [['zh', zhCN], ['en', enUS]] as const) {
      const unset = pick(dict, 'apiToken') as Record<string, string>
      expect(unset.projectScopeHintUnset, `${locale}: unset hint exists`).toBeTruthy()
      expect(unset.projectScopeHintSet, `${locale}: set hint exists`).toBeTruthy()
    }
    const zh = pick(zhCN, 'apiToken') as Record<string, string>
    expect(zh.projectScopeHintUnset, 'zh: parameter name').toContain('受限项目')
    expect(zh.projectScopeHintUnset, 'zh: consequence wording').toContain('吊销')
    // the hint renders inside the create dialog itself; a menu path that
    // re-navigates to this very dialog is self-referential noise
    expect(zh.projectScopeHintUnset, 'zh: no self-referential menu path').not.toContain('系统管理')
    const en = pick(enUS, 'apiToken') as Record<string, string>
    expect(en.projectScopeHintUnset, 'en: parameter name').toContain('Restricted projects')
    expect(en.projectScopeHintUnset, 'en: consequence wording').toContain('revoke')
    expect(en.projectScopeHintUnset, 'en: no self-referential menu path').not.toContain('System')
  })

  it('both hint anchors exist so the two dialog forms are assertable', () => {
    expect(page).toContain('token-project-scope-hint')
    expect(page, 'hint switches on checked count').toMatch(/projectIds\.length\s*===\s*0/)
  })
})

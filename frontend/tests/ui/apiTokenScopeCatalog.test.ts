/**
 * API token scope catalog contract .
 * Locks the three facts the token page owes external agents:
 * - the picker exposes all eight scope families (three existing plus the five
 * MCP families doc/gen/script/exec/review), values keep the mcp. prefix the
 * core validator relies on;
 * - every preset has a description key and both locales carry a non-empty
 * wording for it (zh/en symmetry, locale files are the copy single source);
 * - the create dialog defaults to nothing checked (least privilege) and the
 * legend block carrying the default-unchecked guidance really renders
 * inside the form item (data-test anchor kept for the E2E spectrum).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { API_TOKEN_SCOPE_DESC_KEYS, API_TOKEN_SCOPE_PRESETS } from '../../src/api/apiToken'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'

const page = readFileSync(new URL('../../src/views/token/ApiTokenView.vue', import.meta.url), 'utf8')

const EXPECTED_SCOPES = ['mcp.read', 'mcp.case', 'mcp.mock', 'mcp.doc', 'mcp.gen', 'mcp.script', 'mcp.exec', 'mcp.review']

function pick(obj: Record<string, unknown>, path: string): Record<string, unknown> {
  return path.split('.').reduce<Record<string, unknown>>((acc, key) => (acc?.[key] as Record<string, unknown>) ?? {}, obj)
}

describe('api-token scope catalog contract', () => {
  it('exposes the eight scope families with the mcp. prefix intact', () => {
    for (const scope of EXPECTED_SCOPES) {
      expect(API_TOKEN_SCOPE_PRESETS).toContain(scope)
    }
    for (const scope of API_TOKEN_SCOPE_PRESETS) {
      expect(scope.startsWith('mcp.')).toBe(true)
    }
  })

  it('binds a description key for every preset and both locales carry it', () => {
    for (const scope of API_TOKEN_SCOPE_PRESETS) {
      const key = API_TOKEN_SCOPE_DESC_KEYS[scope]
      expect(key, `missing desc key for ${scope}`).toBeTruthy()
      const shortKey = key.replace('apiToken.', '')
      for (const [name, locale] of [['zh-CN', zhCN], ['en-US', enUS]] as const) {
        const section = pick(locale as unknown as Record<string, unknown>, 'apiToken')
        expect(typeof section[shortKey], `${name} missing ${key}`).toBe('string')
        expect((section[shortKey] as string).length, `${name} empty ${key}`).toBeGreaterThan(4)
      }
    }
  })

  it('create dialog stays default-unchecked and renders the scope legend', () => {
    // form model initializes with no scope checked = least privilege default
    expect(page).toContain('scopes: [],')
    // legend block with the default-unchecked guidance renders in the dialog
    expect(page).toContain('data-test="token-scope-help"')
    expect(page).toContain("t('apiToken.scopeHint')")
    expect(page).toContain('API_TOKEN_SCOPE_DESC_KEYS[s]')
    // the empty-submit denial anchor keeps working (core-side authority untouched)
    expect(page).toContain("t('apiToken.needScopes')")
  })
})

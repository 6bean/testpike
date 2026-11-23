/**
 * path-1 contract (RED-first, ?raw source-scan following the
 * authEditorT372 precedent): the login-state one-click auth template.
 * Pins:
 * - utils/authForm carries the single template builder — token state ->
 * bearer block, cookie_set state -> Cookie header block, both carrying
 * {{CRED:key}} references only (a secret value never appears in the
 * generated shape); storage_state and reference-less rows offer nothing;
 * - both generated shapes pass the save-gate mirror (validateAuth null),
 * so the picker can never emit a row the server would refuse;
 * - AuthFormEditor consumes that builder (no second template copy), loads
 * the project ledger through the admin bridge and renders a searchable
 * picker with an explicit label association;
 * - ApiTestView passes the project segment id to BOTH editor instances
 * (request form + collection defaults);
 * - the bridge mirrors admin GET /api/v1/session-states with
 * projectId + enabledOnly params;
 * - i18n dual track for the picker keys (zh and en).
 */
import { describe, expect, it } from 'vitest'
import {
  sessionStateAuthTemplate,
  isConsumableSessionState,
  validateAuth,
} from '@/utils/authForm'

import editorRaw from '@/views/apitest/AuthFormEditor.vue?raw'
import apiTestRaw from '@/views/apitest/ApiTestView.vue?raw'
import bridgeRaw from '@/api/sessionState.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('login-state auth template: one builder per kind', () => {
  it('token state builds a bearer block referencing the vault key', () => {
    expect(
      sessionStateAuthTemplate({ stateName: 'sso-prod', kind: 'token', credKey: 'session_abc' }),
    ).toEqual({ type: 'bearer', token: '{{CRED:session_abc}}' })
  })

  it('cookie_set state builds a Cookie header block', () => {
    expect(
      sessionStateAuthTemplate({ stateName: 'bff', kind: 'cookie_set', credKey: 'k1' }),
    ).toEqual({ type: 'header', name: 'Cookie', value: '{{CRED:k1}}' })
  })

  it('storage_state offers no API-auth shape (that lane is UI context injection)', () => {
    expect(
      sessionStateAuthTemplate({ stateName: 'snap', kind: 'storage_state', credKey: 'k' }),
    ).toBeNull()
  })

  it('a row without a credential reference offers no template', () => {
    expect(sessionStateAuthTemplate({ stateName: 's', kind: 'token', credKey: '  ' })).toBeNull()
    expect(isConsumableSessionState({ stateName: 's', kind: 'token', credKey: '' })).toBe(false)
    expect(isConsumableSessionState({ stateName: 's', kind: 'cookie_set', credKey: 'k' })).toBe(true)
  })

  it('generated shapes pass the save-gate mirror (picker never emits a refused row)', () => {
    expect(validateAuth(sessionStateAuthTemplate({ stateName: 's', kind: 'token', credKey: 'k' }))).toBeNull()
    expect(validateAuth(sessionStateAuthTemplate({ stateName: 's', kind: 'cookie_set', credKey: 'k' }))).toBeNull()
  })
})

describe('AuthFormEditor wires the shared builder, no second copy', () => {
  it('imports the template builder and the ledger bridge', () => {
    expect(editorRaw).toMatch(/sessionStateAuthTemplate[\s\S]*from '@\/utils\/authForm'/)
    expect(editorRaw).toMatch(/isConsumableSessionState[\s\S]*from '@\/utils\/authForm'/)
    expect(editorRaw).toContain("import { listSessionStates } from '@/api/sessionState'")
  })

  it('renders a searchable labelled picker anchored for the SE digest', () => {
    expect(editorRaw).toContain('data-test="session-state-picker"')
    expect(editorRaw).toMatch(/data-test="session-state-picker"[^>]*filterable/s)
    expect(editorRaw).toContain('label for="af-session-ref"')
  })

  it('loads the picker only when a project slice id is present', () => {
    expect(editorRaw).toMatch(/props\.projectId/)
    expect(editorRaw).toMatch(/listSessionStates\(/)
  })
})

describe('ApiTestView feeds both editor instances the project slice id', () => {
  it('both AuthFormEditor bindings carry :project-id', () => {
    const bound = apiTestRaw.match(/<AuthFormEditor[\s\S]*?:project-id="projectId"[\s\S]*?\/>/g) ?? []
    expect(bound.length).toBe(2)
  })
})

describe('session-state bridge mirrors the admin ledger list', () => {
  it('GET /session-states with projectId + enabledOnly', () => {
    expect(bridgeRaw).toContain("url: '/session-states'")
    expect(bridgeRaw).toMatch(/method:\s*'get'/)
    expect(bridgeRaw).toMatch(/params:\s*\{\s*projectId,\s*enabledOnly\s*\}/)
  })

  it('carries no secret field comment claiming otherwise (metadata view only)', () => {
    expect(bridgeRaw).toMatch(/stateName/)
    expect(bridgeRaw).toMatch(/credKey/)
    expect(bridgeRaw).toMatch(/expiresAt/)
  })
})

describe('i18n dual track for the picker', () => {
  it('sessionRef keys exist in both locales', () => {
    expect(zhRaw).toMatch(/sessionRef:/)
    expect(zhRaw).toMatch(/sessionRefPh:/)
    expect(enRaw).toMatch(/sessionRef:/)
    expect(enRaw).toMatch(/sessionRefPh:/)
  })
})

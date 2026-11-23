/**
 * slice-C2b layout contract for the auth editor (RED-first, ?raw source-scan
 * following orchestrationBuilderT414 precedent).
 * Pins:
 * - ApiTestView wires AuthFormEditor exactly once with a v-model on authJson and
 * the save payload passes formAuth.value (no more hardcoded '{}');
 * - the save pre-gate calls authRef.validateAll before submit;
 * - the editor imports the shared vocabulary from utils/authForm (AUTH_TYPES) and
 * consumes the admin GET /apitest/requests/auth-presets bridge — zero second
 * verdict/type copy inside the component;
 * - openRequest/newRequest both reset the auth form (no stale-row carryover);
 * - every interactive control has an explicit <label for> association (Issues
 * panel zero-tolerance);
 * - empty state = {} (honest no-auth), never a fake default row;
 * - i18n dual track: every apitest.auth* key used by the component exists in
 * both zh-CN and en-US locales (no hard-coded Chinese in the template).
 */
import { describe, expect, it } from 'vitest'

import apiTestRaw from '@/views/apitest/ApiTestView.vue?raw'
import editorRaw from '@/views/apitest/AuthFormEditor.vue?raw'
import apiRaw from '@/api/apiTest.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T372 auth editor: page wiring is single-source', () => {
  it('ApiTestView imports AuthFormEditor from the apitest views folder', () => {
    expect(apiTestRaw).toMatch(/import AuthFormEditor from '@\/views\/apitest\/AuthFormEditor\.vue'/)
  })

  it('the request form binds authJson through v-model; T412 adds the collection-defaults second instance', () => {
    expect(apiTestRaw).toContain('v-model:auth="formAuth"')
    // (collection-level auth, 10-19 user ruling): a second AuthFormEditor
    // hosts the interface-set defaults block with its own v-model target.
    // The single-source rule is per-binding (request form never reuses the
    // defaults editor), not a literal one-tag-per-page count.
    const occurrences = apiTestRaw.match(/<AuthFormEditor/g) ?? []
    expect(occurrences.length).toBe(2)
    expect(apiTestRaw).toContain('v-model:auth="defaultsAuth"')
  })

  it('save payload carries authJson from the form (hardcoded {} is gone)', () => {
    expect(apiTestRaw).toMatch(/authJson:\s*formAuth\.value/)
    expect(apiTestRaw).not.toMatch(/authJson:\s*'\{\}'/)
  })

  it('save pre-gate calls the auth editor validateAll (plain-language pinpoint before submit)', () => {
    expect(apiTestRaw).toMatch(/authRef\.value\?\.validateAll\(\)/)
  })

  it('openRequest and newRequest both reset the auth form (no stale row across switches)', () => {
    const resets = apiTestRaw.match(/formAuth\.value = /g) ?? []
    // openRequest load + newRequest reset = at least two assignment sites
    expect(resets.length).toBeGreaterThanOrEqual(2)
    expect(apiTestRaw).toMatch(/formAuth\.value = req\.authJson \|\| '\{\}'/)
  })
})

describe('T372 auth editor: kernel and preset bridge reuse, zero second copy', () => {
  it('the editor consumes utils/authForm vocabulary (no inline type list)', () => {
    expect(editorRaw).toMatch(/from '@\/utils\/authForm'/)
    expect(editorRaw).toMatch(/AUTH_TYPES/)
    // No inline vocabulary re-implementation.
    expect(editorRaw).not.toMatch(/'api-key',\s*'basic',\s*'bearer'/)
    // Verdict copy lives in utils/authForm.ts only.
    expect(editorRaw).not.toContain('必须是凭据引用')
  })

  it('the editor calls the auth-presets bridge from the api layer', () => {
    expect(editorRaw).toMatch(/listAuthPresets/)
    expect(apiRaw).toMatch(/auth-presets/)
    expect(apiRaw).toMatch(/export function listAuthPresets/)
  })

  it('empty/unconfigured auth serializes to {} (honest no-auth), never a fake default row', () => {
    expect(editorRaw).toMatch(/if \(!authOn\.value\) return \{\}/)
  })

  it('signature presets prefill assembly rows and tell the user secrets stay references', () => {
    // Picking a preset copies its rows into the form (template text only).
    expect(editorRaw).toMatch(/assemblies/)
    expect(editorRaw).toMatch(/authPresetHint/)
  })

  it('every interactive control has an explicit label association', () => {
    const ids = [...editorRaw.matchAll(/id="(af-[\w-]+)"/g)].map((m) => m[1])
    for (const id of ids) {
      expect(editorRaw, `control #${id} must have a <label for>`).toContain(`for="${id}"`)
    }
    expect(ids.length).toBeGreaterThanOrEqual(8)
  })

  it('i18n dual track: apitest.auth* keys used by the editor exist in both locales', () => {
    const keys = [...editorRaw.matchAll(/t\('apitest\.(auth[\w]*)'/g)].map((m) => m[1])
    expect(keys.length).toBeGreaterThanOrEqual(8)
    for (const key of new Set(keys)) {
      expect(zhRaw, `zh-CN missing apitest.${key}`).toContain(`${key}:`)
      expect(enRaw, `en-US missing apitest.${key}`).toContain(`${key}:`)
    }
    // Template must not hard-code Chinese user-facing text.
    expect(editorRaw).not.toMatch(/[\u4e00-\u9fff]/)
  })
})

/**
 * (front half): protocol-crossed editor wiring layout contract
 * (RED-first, ?raw source-scan following the authEditorT372 precedent).
 * Pins:
 * - ApiTestView owns a single transport discriminator (formProtocol) and takes
 * the option vocabulary from utils/assertionForm (TRANSPORT_PROTOCOLS) —
 * no second copy of the http/ws vocabulary in the page or the builder;
 * - openRequest loads req.protocol (legacy rows default http) and newRequest
 * resets to http — no stale protocol across switches;
 * - the save payload carries protocol: formProtocol.value (never dropped —
 * dropping it silently rewrites ws rows back to http at the core gate);
 * - AssertionBuilder receives :protocol and filters its type dropdown by
 * transport face (http = six types, ws = three ws.* types) plus passes
 * protocol to validateAssertionRow (two-arg signature, single source);
 * - the path hint changes with the transport (ws rows must not bet on a
 * ws:// absolute URL — W1c fix-3 plain-language guidance);
 * - i18n dual track: every new apitest protocol key exists in both locales.
 */
import { describe, expect, it } from 'vitest'

import apiTestRaw from '@/views/apitest/ApiTestView.vue?raw'
import builderRaw from '@/components/AssertionBuilder.vue?raw'
import formRaw from '@/utils/assertionForm.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T372 protocol editor: ApiTestView owns the discriminator single-source', () => {
  it('the page takes the transport vocabulary from utils/assertionForm (no inline list)', () => {
    expect(apiTestRaw).toMatch(/TRANSPORT_PROTOCOLS/)
    expect(apiTestRaw).toMatch(/from '@\/utils\/assertionForm'/)
    // A second literal copy of the http/ws pair is forbidden.
    expect(apiTestRaw).not.toMatch(/\[\s*'http',\s*'ws'\s*\]/)
  })

  it('formProtocol defaults to http and both open/new paths maintain it', () => {
    expect(apiTestRaw).toMatch(/const formProtocol = ref\('http'\)/)
    expect(apiTestRaw).toMatch(/formProtocol\.value = req\.protocol \|\| 'http'/)
    expect(apiTestRaw).toMatch(/formProtocol\.value = 'http'/)
  })

  it('the save payload carries the protocol (never hardcoded or dropped)', () => {
    expect(apiTestRaw).toMatch(/protocol:\s*formProtocol\.value/)
  })

  it('the path placeholder follows the transport face', () => {
    // Template refs auto-unwrap: the face check reads formProtocol (no .value).
    expect(apiTestRaw).toMatch(/formProtocol === 'ws' \? t\('apitest\.urlPathPhWs'\)/)
  })

  it('the page renders exactly one transport select bound to formProtocol', () => {
    const occurrences = apiTestRaw.match(/v-model="formProtocol"/g) ?? []
    expect(occurrences.length).toBe(1)
    // Issues-panel zero-tolerance: the select sits behind an explicit label.
    expect(apiTestRaw).toMatch(/<label for="apitest-protocol-select"/)
    expect(apiTestRaw).toMatch(/id="apitest-protocol-select"/)
  })
})

describe('T372 protocol editor: AssertionBuilder crosses the form by transport', () => {
  it('the builder accepts a protocol prop defaulting to http', () => {
    expect(builderRaw).toMatch(/protocol\??:\s*string/)
    expect(builderRaw).toMatch(/protocol:\s*'http'/)
  })

  it('the page passes :protocol down to the builder', () => {
    expect(apiTestRaw).toMatch(/:protocol="formProtocol"/)
  })

  it('the type dropdown filters by transport face from the shared vocabulary', () => {
    expect(builderRaw).toMatch(/WS_ASSERTION_TYPES/)
    // Filtering picks one of the two exported lists per protocol — never a
    // re-declared literal pair (red line: one vocabulary, both ends mirrored).
    expect(builderRaw).toMatch(/props\.protocol === 'ws'/)
    expect(builderRaw).not.toMatch(/\[\s*'ws\.receive',\s*'ws\.order',\s*'ws\.frequency'\s*\]/)
  })

  it('every validateAssertionRow call inside the builder carries the protocol', () => {
    const lines = builderRaw.split('\n').filter((l) => l.includes('validateAssertionRow(') && !l.trim().startsWith('*'))
    expect(lines.length).toBeGreaterThanOrEqual(2)
    for (const line of lines) {
      expect(line).toMatch(/props\.protocol/)
    }
  })

  it('the http-only row fields stay hidden on the ws face', () => {
    // path/name/saveAs inputs are gated behind the http face check.
    expect(builderRaw).toMatch(/isHttp\b.*row\.type === 'body'/s)
  })

  it('ws rows expose the window field (withinMs) and keep it http-free', () => {
    expect(builderRaw).toMatch(/withinMs/)
    expect(builderRaw).toMatch(/isWs/)
  })

  it('protocol flips revalidate the rows (plain-language cross-face errors)', () => {
    expect(builderRaw).toMatch(/watch\(\(\) => props\.protocol/)
  })
})

describe('T372 protocol editor: vocabulary and i18n single-source', () => {
  it('the shared kernel still exports the crossed vocabulary once', () => {
    expect(formRaw).toMatch(/export const TRANSPORT_PROTOCOLS = \['http', 'ws'\]/)
    expect(formRaw).toMatch(/export const WS_ASSERTION_TYPES/)
  })

  it('both locales carry the new protocol keys', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toMatch(/protocol: '.*'/)
      expect(raw).toMatch(/protocolHttp: '.*'/)
      expect(raw).toMatch(/protocolWs: '.*'/)
      expect(raw).toMatch(/urlPathPhWs: '.*'/)
    }
  })
})

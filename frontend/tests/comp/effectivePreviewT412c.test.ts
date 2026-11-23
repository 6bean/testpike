/**
 * final-effective preview wiring layout contract (RED-first,
 * ?raw source-scan following the protocolEditorWiringT372 precedent).
 * Pins:
 * - apiTest.ts mirrors the admin EffectivePreviewDto shape and exposes one
 * fetch function bound to GET /apitest/requests/{id}/effective (single
 * source for the preview payload — the page never re-implements the merge);
 * - ApiTestView renders the preview as one collapsible section in the request
 * workspace: the trigger is gated while requestId is null (a brand-new
 * unsaved request has no server-side verdict to show — honest disabled +
 * plain-language hint, no local second copy of the merge maths);
 * - every merged header row carries its provenance chip (request vs
 * collection) and the auth verdict renders all four sources
 * (request / collection / none-explicit / none) — inheritance is never
 * - the inherited face names the owning collection (collectionName echo) so
 * the user can tell WHICH set the defaults came from;
 * - i18n dual track: every new apitest preview key exists in both locales.
 */
import { describe, expect, it } from 'vitest'

import apiRaw from '@/api/apiTest.ts?raw'
import apiTestRaw from '@/views/apitest/ApiTestView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T412 blade 3c: api layer mirrors the effective preview contract', () => {
  it('exposes one fetch bound to the admin effective endpoint', () => {
    expect(apiRaw).toMatch(/export function getEffectivePreview\(id: number\)/)
    expect(apiRaw).toMatch(/\/apitest\/requests\/\$\{id\}\/effective/)
  })

  it('mirrors the EffectivePreviewDto record shape byte-for-byte', () => {
    for (const field of ['requestId', 'collectionId', 'collectionName', 'headers', 'headersJson', 'authJson', 'authSource']) {
      expect(apiRaw).toContain(`interface EffectivePreviewItem`)
      expect(apiRaw.slice(apiRaw.indexOf('interface EffectivePreviewItem'))).toMatch(new RegExp(`\\b${field}:`))
    }
    // Header rows keep the per-row provenance field (the chips consume it).
    expect(apiRaw).toMatch(/interface EffectiveHeaderItem[\s\S]{0,200}\bsource:/)
  })
})

describe('T412 blade 3c: ApiTestView renders the final-effective preview', () => {
  it('fetches through the api layer (never re-merges locally)', () => {
    expect(apiTestRaw).toMatch(/getEffectivePreview/)
    expect(apiTestRaw).toMatch(/from '@\/api\/apiTest'/)
    // The page must not contain its own case-insensitive header merge —
    // that judgement is single-sourced in the core resolver.
    expect(apiTestRaw).not.toMatch(/toLowerCase\(\)[\s\S]{0,120}hasOwnProperty/)
  })

  it('gates the preview trigger while the request is unsaved', () => {
    expect(apiTestRaw).toMatch(/:disabled="requestId === null"/)
    expect(apiTestRaw).toMatch(/data-test="apitest-effective-toggle"/)
  })

  it('renders a provenance chip per merged header row', () => {
    expect(apiTestRaw).toMatch(/data-test="apitest-effective-header-row"/)
    // Both source faces have a rendered label (request / collection).
    expect(apiTestRaw).toMatch(/apitest\.effectiveSourceRequest/)
    expect(apiTestRaw).toMatch(/apitest\.effectiveSourceCollection/)
  })

  it('renders all four auth verdict faces plus the collection echo', () => {
    for (const key of ['effectiveAuthRequest', 'effectiveAuthCollection', 'effectiveAuthNoneExplicit', 'effectiveAuthNone']) {
      expect(apiTestRaw).toMatch(new RegExp(`apitest\\.${key}`))
    }
    expect(apiTestRaw).toMatch(/collectionName/)
  })

  it('i18n dual track: every preview key exists in both locales', () => {
    const keys = [
      'effectiveToggle', 'effectivePreviewHint',
      'effectiveHeaders', 'effectiveAuth', 'effectiveSourceRequest',
      'effectiveSourceCollection', 'effectiveAuthRequest', 'effectiveAuthCollection',
      'effectiveAuthNoneExplicit', 'effectiveAuthNone', 'effectiveNeedsSave',
      'effectiveFromCollection',
    ]
    for (const k of keys) {
      expect(zhRaw).toContain(`${k}:`)
      expect(enRaw).toContain(`${k}:`)
    }
  })
})

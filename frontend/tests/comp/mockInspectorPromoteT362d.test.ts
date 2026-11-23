/**
 * layout contract for the inspector "make this request a stub"
 * one click (?raw source-scan mirror of the mockRecordingT362c posture).
 * Contract authorities:
 * - promote entry lives in the detail drawer (one row's snapshot, one click),
 * gated on the row actually carrying a recorded answer — the same refusal
 * rule core enforces, mirrored so the button never invites a rejected call;
 * - the one click posts through the admin bridge only
 * (POST /mock/spaces/{spaceId}/hits/{hitId}/promote) — never direct core;
 * - i18n dual-track: every promote-facing key exists in both locale bundles
 * (zero hard-coded Chinese in the view).
 */
import { describe, expect, it } from 'vitest'

import viewRaw from '@/views/mock/MockInspectorView.vue?raw'
import apiRaw from '@/api/mock?raw'
import zhRaw from '@/utils/locales/zh-CN?raw'
import enRaw from '@/utils/locales/en-US?raw'

describe('T362d inspector promote: layout contract', () => {
  it('detail drawer carries the promote entry gated on a recorded answer', () => {
    expect(viewRaw).toContain('data-test="mock-inspector-promote"')
    expect(viewRaw).toMatch(/:disabled="!detailHasResponse"/)
    expect(viewRaw).toContain('const detailHasResponse = computed(')
    // honest hint beside the disabled button (no after-the-fact generic error)
    expect(viewRaw).toContain("t('mockInspector.promoteNoResponse')")
  })

  it('promote posts through the admin bridge mirror only (no direct core)', () => {
    expect(apiRaw).toContain('export function promoteHitToStub')
    expect(apiRaw).toContain('/mock/spaces/${spaceId}/hits/${hitId}/promote')
    expect(apiRaw).toContain("method: 'post'")
    expect(viewRaw).toContain('promoteHitToStub(spaceId.value, row.id)')
  })

  it('idempotent re-click distinguishes created from skipped in plain words', () => {
    expect(viewRaw).toContain("t('mockInspector.promoteCreated'")
    expect(viewRaw).toContain("t('mockInspector.promoteSkipped')")
  })

  it('view holds zero hard-coded Chinese outside i18n calls', () => {
    const cjkInTemplate = viewRaw
      .split('\n')
      .filter(
        (line) =>
          /[\u4e00-\u9fff]/.test(line)
          && !/^\s*(\/\/|<!--|\*)/.test(line)
          && !line.includes("t('"),
      )
      // data-value fallbacks ('—') and the scoped comment blocks are exempt lines
      .filter((line) => !line.includes('—'))
    expect(cjkInTemplate).toEqual([])
  })

  it('i18n dual-track: every promote key exists in both bundles', () => {
    for (const key of ['promoteBtn', 'promoteNote', 'promoteNoResponse',
      'promoteCreated', 'promoteSkipped', 'promoteFailed']) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
  })
})

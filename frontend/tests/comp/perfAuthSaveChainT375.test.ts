/**
 * GA-1 auth-flagged login step save chain .
 *
 * Defect: the wizard's step rows only carried action/method/url through
 * buildPayload, so the auth flag (and headers/body/saveAs/expect edits) was
 * dropped on every save — the engine templates do compile `auth==true` into the
 * fixed "auth" tag and perf_worker buckets those points out of the business
 *
 * Contract asserted here:
 * 2. buildPayload carries auth plus the optional DSL fields instead of a
 * three-key whitelist, and omits keys the user never filled (legacy
 * scenarios with no auth key must not gain one — zero-drift);
 * 3. the steps hint mentions the auth bucketing effect so the checkbox is not
 * an unlabeled switch.
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import groupsRaw from '@/views/perf/journeyGroups.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'

describe('GA-1 auth step save chain in the perf wizard', () => {
  it('step rows render an auth checkbox with a stable anchor', () => {
    expect(wizardRaw).toContain('data-test="pw-step-auth"')
  })

  it('buildPayload no longer collapses steps to action/method/url alone', () => {
    // RED pinned the old one-expression whitelist map; GREEN keeps the base
    // three keys only as the starting shape and then layers the optional DSL
    // fields on top, so the ban is on the collapsing form, not the base literal.
    expect(wizardRaw).not.toMatch(
      /steps: form\.steps\.map\(\(r\) => \(r\.action === 'http\.request'/,
    )
    // The serializer moved into journeyGroups.stepForSave when U-4 added the
    // B-3 pair to the same round-trip rule (red line 6: one serializer, both
    // slices consume it) — the wizard now maps through that single source.
    expect(wizardRaw).toMatch(/steps: form\.steps\.map\(stepForSave\)/)
    // auth is carried explicitly (the flag the engine templates consume).
    const serializerBlock = groupsRaw.slice(
      groupsRaw.indexOf('export function stepForSave'),
    )
    expect(serializerBlock).toMatch(/if \(r\.auth\) base\.auth = true/)
  })

  it('optional DSL fields ride along only when filled (legacy zero-drift)', () => {
    // spread-then-delete-empty or conditional spread — either keeps absent keys absent
    const serializerBlock = groupsRaw.slice(
      groupsRaw.indexOf('export function stepForSave'),
    )
    expect(serializerBlock).toContain('filled')
    for (const field of ['headers', 'body', 'saveAs']) {
      expect(serializerBlock).toContain(field)
    }
  })

  it('the steps hint explains what the auth flag does (bucketing, not a bare switch)', () => {
    const hintBlock = zhRaw.slice(zhRaw.indexOf('stepsHint'), zhRaw.indexOf('stepsHint') + 400)
    expect(hintBlock).toContain('登录请求')
    expect(hintBlock).toMatch(/认证|不计/)
  })
})

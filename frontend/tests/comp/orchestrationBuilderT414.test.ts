/**
 * layout contract for the request orchestration form mirror.
 * Pins (RED-first posture, ?raw source-scan following mockHeaderLayoutT396):
 * - the page wires OrchestrationBuilder into the request form (no second
 * selector/vocabulary copy — the builder consumes utils/orchestrationForm
 * which mirrors the core ApiOrchestrationContract, shared fixture both ends);
 * - the save payload carries preconditionJson/loopJson through the admin
 * bridge (single source = core jsonb columns; the form never keeps its own
 * execution-state copy);
 * - labels are explicit for every control (Issues-panel zero-tolerance),
 * empty/unconfigured = {} (straight-line), never a fake default row;
 * - the TS mirror module exists and exports both validators.
 */
import { describe, expect, it } from 'vitest'

import apiTestRaw from '@/views/apitest/ApiTestView.vue?raw'
import builderRaw from '@/views/apitest/OrchestrationBuilder.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import { validateLoopRow, validatePreconditionRow } from '@/utils/orchestrationForm'

describe('T414 orchestration wiring: the request form hosts the builder once', () => {
  it('ApiTestView imports OrchestrationBuilder and binds both v-models', () => {
    expect(apiTestRaw).toMatch(/import OrchestrationBuilder from/)
    expect(apiTestRaw).toContain('v-model:precondition="formPrecondition"')
    expect(apiTestRaw).toContain('v-model:loop="formLoop"')
  })

  it('save payload passes preconditionJson + loopJson to the admin bridge', () => {
    expect(apiTestRaw).toMatch(/preconditionJson:\s*formPrecondition\.value/)
    expect(apiTestRaw).toMatch(/loopJson:\s*formLoop\.value/)
  })

  it('save pre-gate calls the builder validateAll (plain-language pinpoint before submit)', () => {
    expect(apiTestRaw).toMatch(/orchestrationRef\.value\?\.validateAll\(\)/)
  })

  it('builder has no second vocabulary: verdicts come from utils/orchestrationForm only', () => {
    expect(builderRaw).toMatch(/from '@\/utils\/orchestrationForm'/)
    // No inline re-implementation of enum lists in the component.
    expect(builderRaw).not.toMatch(/'exists',\s*'absent'.*\n.*'eq',\s*'ne'/)
  })
})

describe('T414 orchestration builder: honest empty state + labelled controls', () => {
  it('unconfigured rows serialize to {} (straight-line), never a fake default gate', () => {
    expect(builderRaw).toMatch(/function buildPrecondition\(\): Record<string, unknown> \{\s*return preOn\.value \? buildRow\(pre\) : \{\}/)
    expect(builderRaw).toMatch(/if \(!loopOn\.value\) return \{\}/)
  })

  it('every interactive control has an explicit label association (Issues panel zero-tolerance)', () => {
    const ids = [...builderRaw.matchAll(/id="(ob-[\w-]+)"/g)].map((m) => m[1])
    for (const id of ids) {
      expect(builderRaw, `control #${id} must have a <label for>`).toContain(`for="${id}"`)
    }
    expect(ids.length).toBeGreaterThanOrEqual(8)
  })
})

describe('T414 orchestration form kernel: contract surface used by the builder', () => {
  it('absent/empty = legal straight-line on both row kinds (fixture positives: null / {})', () => {
    expect(validatePreconditionRow(null)).toBe(null)
    expect(validatePreconditionRow(undefined)).toBe(null)
    expect(validatePreconditionRow({})).toBe(null)
    expect(validateLoopRow(null)).toBe(null)
    expect(validateLoopRow({})).toBe(null)
  })

  it('scalar garbage fails closed (same as the core save gate fixture)', () => {
    expect(validatePreconditionRow('status==200')).toContain('前置条件必须是一个表单对象')
    expect(validateLoopRow('loop x3')).toContain('循环配置必须是一个表单对象')
  })

  it('until loop without a stop row is refused (executor fail-closed same vocabulary)', () => {
    expect(validateLoopRow({ mode: 'until', maxTimes: 5 })).toContain('until 停止条件行')
  })
})

describe('T414 i18n dual track: orchestration keys exist in both locales', () => {
  const zh = zhRaw as unknown as string
  const en = enRaw as unknown as string
  for (const key of ['orchestration:', 'preconditionEnable:', 'loopEnable:', 'loopUntil:', 'preconditionHint:']) {
    it(`zh-CN and en-US both define apitest.${key.slice(0, -1)}`, () => {
      expect(zh, `zh missing ${key}`).toContain(key)
      expect(en, `en missing ${key}`).toContain(key)
    })
  }
})

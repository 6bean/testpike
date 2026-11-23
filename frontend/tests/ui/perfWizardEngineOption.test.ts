/**
 * W-d third-engine wizard contract (JMeter engine option).
 *
 * What this pins: the scenario wizard carries a load-engine selector fed
 * solely by the catalogue door (GET /perf/engines — rows single-sourced from
 * the dispatch value domain server-side), the declared engine rides the save
 * payload, an edit round-trip keeps the binding, and the selected engine's
 * rate-caliber caveat renders VERBATIM from the server row (the disclosure
 * component owns the wording; a paraphrased client copy would fork it).
 *
 * Why a gate: hard-coding an option list in the frontend drifts from the
 * engine value domain the moment a fourth engine lands (single-source rule),
 * and a silently dropped binding would make the wizard lie about which
 * engine runs the scenario — the approximation caliber only reaches the user
 * while the caveat survives the round trip.
 */
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import apiPerfRaw from '@/api/perf.ts?raw'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'

function section(raw: string, start: string, end: string): string {
  const from = raw.indexOf(start)
  expect(from, `marker missing: ${start}`).toBeGreaterThanOrEqual(0)
  const to = raw.indexOf(end, from)
  expect(to, `end marker missing: ${end}`).toBeGreaterThan(from)
  return raw.slice(from, to)
}

describe('api mirror: engine catalogue door', () => {
  it('listPerfEngines rides GET /perf/engines (admin bridge verbatim rows)', () => {
    expect(apiPerfRaw).toMatch(/export function listPerfEngines/)
    const body = section(apiPerfRaw, 'export function listPerfEngines', '\nexport ')
    expect(body).toMatch(/url: '\/perf\/engines'/)
    expect(body).toMatch(/method: 'get'/)
  })
  it('catalogue row mirrors the server shape (engine + nullable caveat)', () => {
    const row = section(apiPerfRaw, 'export interface PerfEngineOption', '\nexport ')
    expect(row).toMatch(/engine: string/)
    expect(row).toMatch(/caveat: string \| null/)
  })
  it('save payload declares the optional engine pin (null = default routing)', () => {
    const payload = section(apiPerfRaw, 'export interface PerfScenarioSavePayload', '\nexport ')
    expect(payload).toMatch(/engine\?: string \| null/)
  })
  it('scenario item exposes the persisted binding for the edit round-trip', () => {
    const item = section(apiPerfRaw, 'export interface PerfScenarioItem', '\nexport ')
    expect(item).toMatch(/engine\??: string \| null/)
  })
})

describe('wizard: engine option surface', () => {
  it('imports the catalogue door (never a hard-coded option list)', () => {
    expect(wizardRaw).toMatch(/listPerfEngines/)
    expect(wizardRaw).not.toMatch(/const ENGINES = \[/)
  })
  it('form carries the engine pin and the catalogue loads per session', () => {
    expect(wizardRaw).toMatch(/engine: null as string \| null/)
    expect(wizardRaw).toMatch(/async function loadEngines/)
    expect(wizardRaw).toMatch(/void loadEngines\(\)/)
  })
  it('payload and edit hydration ride the same binding (no silent drop)', () => {
    const payload = section(wizardRaw, 'function buildPayload', 'function nextStep')
    expect(payload).toMatch(/engine: form\.engine/)
    expect(wizardRaw).toMatch(/form\.engine = sc\.engine \?\? null/)
  })
  it('selector renders catalogue rows with display names and the caveat line', () => {
    const pressure = section(wizardRaw, 'data-test="pw-step-pressure"', 'data-test="pw-model-fixed-vu"')
    expect(pressure).toMatch(/data-test="pw-engine"/)
    expect(pressure).toMatch(/perf\.engineName\.\$\{o\.engine\}/)
    expect(pressure).toMatch(/data-test="pw-engine-caveat"/)
  })
})

describe('locales: engine wording is bilingual and complete', () => {
  const z = zhCN as Record<string, any>
  const e = enUS as Record<string, any>
  it('wizard engine keys exist on both tracks', () => {
    for (const key of ['engine', 'enginePh', 'engineHint', 'engineLoadFail']) {
      expect(typeof z.perf.wizard[key], `zh perf.wizard.${key}`).toBe('string')
      expect(typeof e.perf.wizard[key], `en perf.wizard.${key}`).toBe('string')
    }
  })
  it('display names cover the dispatch value domain', () => {
    for (const engine of ['K6', 'GATLING', 'JMETER']) {
      expect(typeof z.perf.engineName[engine], `zh engineName.${engine}`).toBe('string')
      expect(typeof e.perf.engineName[engine], `en engineName.${engine}`).toBe('string')
    }
  })
})

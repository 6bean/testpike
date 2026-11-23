/**
 * Default full-enumeration contract (scenario circle-selection discoverability):
 * a batch submit that never ran the preview must first auto-enumerate the five
 * scenario kinds and submit fully selected — one requirement point becomes
 * several cases by default. Three shapes this file pins shut:
 *  1. the submit path branches on the echoed K8 flag (shipped default on) and
 *     runs the SAME enumeration single source as the manual preview button
 *     (no second enumeration implementation, no silent whole-row fallback);
 *  2. the batch action zone carries a visible hint line (data-test anchor)
 *     naming the five kinds, and the off-state names the settings page plus
 *     the config key literally (R32 guidance door);
 *  3. both locales carry every new key (the i18n terminal gate would also
 *     catch it, this pins the wording contract itself).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const SRC = new URL('../../src', import.meta.url).pathname

const genView = readFileSync(`${SRC}/views/ai/AiGenerateView.vue`, 'utf8')
const aiApi = readFileSync(`${SRC}/api/ai.ts`, 'utf8')
const zh = readFileSync(`${SRC}/utils/locales/zh-CN.ts`, 'utf8')
const en = readFileSync(`${SRC}/utils/locales/en-US.ts`, 'utf8')

describe('batch default full-enumeration (auto scenario preview on submit)', () => {
  it('submitBatch runs the shared enumeration source before estimating when unselected', () => {
    const fn = genView.match(/async function submitBatch\(\)[\s\S]*?\n}/)
    expect(fn, 'submitBatch present').not.toBeNull()
    const body = fn![0]
    // flag-gated auto branch, before the cost estimate call
    expect(body).toMatch(/if \(!previewActive\.value && autoEnumerate\.value === 1\)/)
    expect(body.indexOf('await startScenarioPreview()')).toBeLessThan(body.indexOf('estimateBatchGenerate'))
    // honest tri-state: expanded keeps the multiplier path, a dead door states itself
    expect(body).toContain("autoExpanded = status === 'expanded'")
    expect(body).toContain("autoEnumFailed = status === 'aborted'")
    expect(body).toContain('confirmAutoFailedLine')
  })

  it('the cost dialog promises exactly the tasks that will be sent', () => {
    const body = genView.match(/async function submitBatch\(\)[\s\S]*?\n}/)![0]
    // the real payload is computed before the dialog and the dialog count
    // reads that same source (no double conversion); key off keeps raw rows
    expect(body.indexOf('const finalPayload')).toBeLessThan(body.indexOf('ElMessageBox.confirm'))
    expect(body).toContain('count: previewActive.value ? finalPayload.items.length : est.count')
  })

  it('startScenarioPreview answers the tri-state honestly', () => {
    const fn = genView.match(/async function startScenarioPreview\(\)[\s\S]*?\n}/)
    expect(fn, 'startScenarioPreview present').not.toBeNull()
    expect(fn![0]).toContain("Promise<'expanded' | 'off' | 'aborted'>")
    expect(fn![0]).toContain("return 'aborted'")
    expect(fn![0]).toContain("return 'off'")
    expect(fn![0]).toContain("return 'expanded'")
  })

  it('the batch action zone carries the hint line with anchors', () => {
    expect(genView).toContain('data-test="ai-gen-auto-enum-hint"')
    expect(genView).toContain('data-test="ai-gen-auto-enum-running"')
    expect(genView).toContain('aiGenerate.autoEnumHintOn')
    expect(genView).toContain('aiGenerate.autoEnumHintOff')
  })

  it('the limit door echoes the flag and the page consumes the off signal', () => {
    expect(aiApi).toContain('autoEnumerate?: number')
    expect(genView).toContain('if (r && r.autoEnumerate === 0) autoEnumerate.value = 0')
  })

  it('both locales carry every new key, and the off state names the entry and the key', () => {
    for (const key of ['autoEnumHintOn', 'autoEnumHintOff', 'autoEnumRunning', 'confirmAutoLine', 'confirmAutoFailedLine']) {
      expect(zh).toContain(`${key}:`)
      expect(en).toContain(`${key}:`)
    }
    // R32 guidance: settings page literal + config key literal, no bare error
    expect(zh).toContain('ai.gen.batch.auto-enumerate.enabled')
    expect(en).toContain('ai.gen.batch.auto-enumerate.enabled')
    // the five kinds are named on screen (user-facing vocabulary, not a code id)
    expect(zh).toContain('主流程、等价类、反例、边界、状态组合')
  })
})

describe('single-line submit rides the same default-enumeration door', () => {
  it('with the switch on, one requirement becomes a one-row batch submit (several cases)', () => {
    const fn = genView.match(/async function submit\(\)[\s\S]*?\n}/)
    expect(fn, 'submit present').not.toBeNull()
    const body = fn![0]
    expect(body).toContain('if (autoEnumerate.value === 1)')
    // lands the text as one batch row, keeps the requirement link, hands off
    // to the batch door (auto-enumerate + full selection + cost confirm)
    expect(body).toContain('batchRows.value = linked ? [{ text, requirementId: linked }] : [{ text }]')
    expect(body).toContain("mode.value = 'batch'")
    expect(body.indexOf('await submitBatch()')).toBeLessThan(body.indexOf('generateCases'))
  })

  it('the legacy one-task shape survives only behind the switch (escape hatch)', () => {
    const fn = genView.match(/async function submit\(\)[\s\S]*?\n}/)
    expect(fn![0]).toContain('generateCases(')
    // and the single form says so up front (discoverability line on the page)
    expect(genView).toContain('data-test="ai-gen-single-auto-hint"')
  })
})

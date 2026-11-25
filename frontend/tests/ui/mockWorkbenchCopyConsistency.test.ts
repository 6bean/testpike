import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Screen-copy contracts for the mock workbench wording family (vision review
 * follow-up, five faces at 1920).
 *
 * Why: the review found four shipped-copy defects on this page family —
 * empty-state hints pointing at a button "at the top right" while the create
 * button really sits at the left of the filter band (a false path under the
 * error-guidance rule), a self-referential "honest report" phrase on the
 * offline health banner (internal quality language on screen), a "we never
 * fabricate samples" boast instead of an action hint on the traffic-empty
 * state, and the scenarios tab labelled with a shorter synonym of the module
 * title so one concept showed two names side by side.
 *
 * These assertions pin the corrected shapes: no corner-direction words in
 * empty-state hints, the tab label equals the embedded module title in both
 * languages, and no self-proving wording in the inspector notices (the
 * guidance instead names what the page does next).
 */

const ROOT = resolve(__dirname, '../..')

const ZH_FILE = 'src/utils/locales/zh-CN.ts'
const EN_FILE = 'src/utils/locales/en-US.ts'

function source(rel: string): string {
  return readFileSync(resolve(ROOT, rel), 'utf8')
}

/** Read one top-level `key: 'value'` line inside a given namespace block. */
function nsValue(text: string, ns: string, key: string): string {
  const lines = text.split(/\r?\n/)
  const start = lines.findIndex((l) => new RegExp(`^  ${ns}: \\{`).test(l))
  expect(start, `namespace ${ns} missing`).toBeGreaterThan(-1)
  const re = new RegExp(`^    ${key}: '(.*)',?$`)
  for (let i = start + 1; i < lines.length; i++) {
    if (/^  [a-zA-Z]+: \{/.test(lines[i]) || /^  \},?$/.test(lines[i])) break
    const m = lines[i].match(re)
    if (m) return m[1]
  }
  expect.fail(`key ${ns}.${key} not found`)
}

const CORNER_ZH = /右上角|左上角|右下角|左下角/

describe('mock empty-state hints name the action, never a screen corner', () => {
  it('zh scenario and recording empty hints carry no direction words', () => {
    const zh = source(ZH_FILE)
    for (const [ns, key] of [
      ['mockScenarios', 'empty'],
      ['mockRecording', 'empty'],
      ['mockInspector', 'emptyHits'],
    ] as const) {
      expect(nsValue(zh, ns, key), `${ns}.${key}`).not.toMatch(CORNER_ZH)
    }
  })

  it('en scenario and recording empty hints carry no corner wording', () => {
    const en = source(EN_FILE)
    const corner = /top[- ]right|top right|corner/i
    for (const [ns, key] of [
      ['mockScenarios', 'empty'],
      ['mockRecording', 'empty'],
      ['mockInspector', 'emptyHits'],
    ] as const) {
      expect(nsValue(en, ns, key), `${ns}.${key}`).not.toMatch(corner)
    }
  })

  it('the traffic-empty hint tells the user what to do next', () => {
    expect(nsValue(source(ZH_FILE), 'mockInspector', 'emptyHits')).toMatch(/发起请求/)
    expect(nsValue(source(EN_FILE), 'mockInspector', 'emptyHits')).toMatch(/send a request/i)
  })
})

describe('inspector notices state facts, not internal quality promises', () => {
  it('the offline banner drops the self-reporting phrase and names the retry', () => {
    const zh = nsValue(source(ZH_FILE), 'mockInspector', 'healthOffline')
    const en = nsValue(source(EN_FILE), 'mockInspector', 'healthOffline')
    expect(zh).not.toMatch(/如实/)
    expect(en).not.toMatch(/honest/i)
    // the verified recovery path: auto refresh polls and the notice clears itself
    expect(zh).toMatch(/自动/)
    expect(en).toMatch(/auto refresh/i)
  })

  it('the traffic-empty hint drops the no-samples boast', () => {
    expect(nsValue(source(ZH_FILE), 'mockInspector', 'emptyHits')).not.toMatch(/不造样本|只呈现真实/)
    expect(nsValue(source(EN_FILE), 'mockInspector', 'emptyHits')).not.toMatch(/really arrived/i)
  })
})

describe('one concept one word inside the workbench', () => {
  it('the scenarios tab label equals the embedded module title (zh)', () => {
    const zh = source(ZH_FILE)
    expect(nsValue(zh, 'mockWorkbench', 'tabScenarios')).toBe(
      nsValue(zh, 'mockScenarios', 'pageTitle'),
    )
  })

  it('the scenarios tab label equals the embedded module title (en)', () => {
    const en = source(EN_FILE)
    expect(nsValue(en, 'mockWorkbench', 'tabScenarios')).toBe(
      nsValue(en, 'mockScenarios', 'pageTitle'),
    )
  })
})

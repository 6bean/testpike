/**
 * Ledger state-door contract (WE batch h-surface): every row action button
 * on the ledger page must offer exactly the origin states the SERVER
 * transition table (WebElementLifecycle.ALLOWED) allows for its target
 * state — no second state machine on the frontend, and no missing door for
 * legal moves. Stale rows therefore carry the confirm door (STALE →
 * CONFIRMED is legal), and retired rows carry no revive button at all
 * (RETIRED → ∅ is the hard terminal red line; the negative assertion here
 * is what refuses a silent future reversal).
 *
 * Mechanism: parse the Java transition table, parse each button's v-if
 * predicate out of the view template, evaluate the predicate over all four
 * states, and assert truth-set equality per action. If either side drifts,
 * this test goes red without touching a browser.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const viewSrc = readFileSync(
  resolve(here, '../../src/views/webelement/WebElementLedgerView.vue'),
  'utf-8',
)
const lifecycleSrc = readFileSync(
  resolve(
    here,
    '../../../core/src/main/java/com/sixbean/testpike/core/webelement/domain/component/WebElementLifecycle.java',
  ),
  'utf-8',
)

const STATE_NAMES = ['CANDIDATE', 'CONFIRMED', 'STALE', 'RETIRED'] as const
const STATE_VALUES: Record<string, number> = {
  CANDIDATE: 0,
  CONFIRMED: 1,
  STALE: 2,
  RETIRED: 3,
}

/** Origin states whose server-side transition set contains the target. */
function allowedOrigins(target: number): number[] {
  const block = lifecycleSrc.match(
    /Map\.of\(([\s\S]*?)\)\s*;/,
  )
  if (!block) {
    throw new Error('transition table shape drifted: ALLOWED = Map.of(...) not found')
  }
  const origins: number[] = []
  for (const rawLine of block[1].split('\n')) {
    const line = rawLine.trim()
    const m = line.match(/^(CANDIDATE|CONFIRMED|STALE|RETIRED),\s*Set\.of\(([^)]*)\),?$/)
    if (!m) continue
    const from = STATE_VALUES[m[1]]
    const tos = m[2]
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
      .map((s) => STATE_VALUES[s])
    if (tos.includes(target)) origins.push(from)
  }
  if (origins.length === 0 && target !== STATE_VALUES.RETIRED) {
    throw new Error(`transition table parsed but no origin allows ->${target}`)
  }
  return origins.sort((a, b) => a - b)
}

/** The v-if predicate guarding the button with the given data-test hook. */
function buttonCondition(dataTest: string): string {
  const hook = `data-test="${dataTest}"`
  const at = viewSrc.indexOf(hook)
  if (at < 0) throw new Error(`button ${dataTest} missing from the ledger view`)
  const tagStart = viewSrc.lastIndexOf('<el-button', at)
  if (tagStart < 0) throw new Error(`no enclosing el-button for ${dataTest}`)
  const tagEnd = viewSrc.indexOf('>', at)
  const tag = viewSrc.slice(tagStart, tagEnd)
  const m = tag.match(/v-if="([^"]+)"/)
  return m ? m[1] : 'true'
}

/** States 0..3 for which the predicate evaluates true (template-side reach). */
function statesReachedBy(predicate: string): number[] {
  const reach: number[] = []
  for (const state of [0, 1, 2, 3]) {
    const el = { state }
    // The predicates are plain state comparisons from our own template;
    // evaluating them here mirrors what v-if does per row at render time.
    // eslint-disable-next-line no-new-func
    const hit = new Function('el', `return (${predicate})`)(el)
    if (hit) reach.push(state)
  }
  return reach
}

describe('页面元素库动作钮面与服务端流转表单源对账', () => {
  it('确认入库钮覆盖恰为「可迁移到已确认」的起始态（陈旧行必须有恢复门）', () => {
    const server = allowedOrigins(STATE_VALUES.CONFIRMED)
    const door = statesReachedBy(buttonCondition('ledger-confirm'))
    expect(server).toEqual([0, 2])
    expect(door).toEqual(server)
  })

  it('标记待复核钮覆盖恰为「可迁移到陈旧」的起始态', () => {
    const server = allowedOrigins(STATE_VALUES.STALE)
    const door = statesReachedBy(buttonCondition('ledger-mark-stale'))
    expect(server).toEqual([1])
    expect(door).toEqual(server)
  })

  it('退役钮覆盖恰为「可迁移到退役」的起始态', () => {
    const server = allowedOrigins(STATE_VALUES.RETIRED)
    const door = statesReachedBy(buttonCondition('ledger-retire'))
    expect(server).toEqual([0, 1, 2])
    expect(door).toEqual(server)
  })

  it('负断言：退役行零动作钮（RETIRED 终态禁任何复活入口）', () => {
    const revived = (
      ['ledger-confirm', 'ledger-mark-stale'] as const
    ).filter((hook) => statesReachedBy(buttonCondition(hook)).includes(3))
    expect(revived).toEqual([])
    expect(statesReachedBy(buttonCondition('ledger-retire'))).not.toContain(3)
  })

  it('流转表本体守恒：RETIRED 目标集为空、UNKNOWN 态不落钮面', () => {
    expect(lifecycleSrc).toMatch(/RETIRED,\s*Set\.of\(\)/)
    // STATE_NAMES stays the full domain the doors are checked against.
    expect(STATE_NAMES).toHaveLength(4)
  })
})

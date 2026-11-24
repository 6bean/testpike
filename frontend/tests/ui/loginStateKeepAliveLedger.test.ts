/**
 * Login-state keep-alive facade contract (ledger page segment).
 *
 * Disk truth = source text (same posture as the ledger mirror contract):
 * the admin LoginStateController owns the keep-alive doors (PATCH
 * /session-states/{id}/keepalive plus the keepalive-events / keepalive-runs
 * read doors); the front-end must mirror each one, and the ledger page must
 * keep the iron rules visible in structure:
 * - the keep-alive column family is surfaced for every row (state, cadence
 *   result, last round) instead of hidden behind a click;
 * - a paused row names the cause verbatim plus the literal way back out plus
 *   an action button (three-element error rule);
 * - refusal wording belongs to core: the page shows the server sentence and
 *   owns no second range validation;
 * - the replay doors ride the SHARED tail/history components (one chain).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const apiSrc = readFileSync(join(SRC, 'api', 'sessionState.ts'), 'utf8')
const eventsSrc = readFileSync(join(SRC, 'api', 'taskEvents.ts'), 'utf8')
const viewSrc = readFileSync(join(SRC, 'views', 'session', 'LoginStateListView.vue'), 'utf8')
const zhSrc = readFileSync(join(SRC, 'utils', 'locales', 'zh-CN.ts'), 'utf8')
const enSrc = readFileSync(join(SRC, 'utils', 'locales', 'en-US.ts'), 'utf8')

function templateOf(src: string): string {
  const start = src.indexOf('<template>')
  expect(start, 'view must carry a template block').toBeGreaterThan(-1)
  let right = src.indexOf('<script', start)
  const styleAt = src.indexOf('<style', start)
  if (styleAt > start && (right < 0 || styleAt < right)) right = styleAt
  return src.slice(start, right < 0 ? src.length : right)
}

function scriptOf(src: string): string {
  const start = src.indexOf('<script setup')
  expect(start, 'view must carry a script block').toBeGreaterThan(-1)
  return src.slice(start, src.indexOf('</script>'))
}

describe('keep-alive api mirror (admin /session-states keep-alive doors)', () => {
  it('exports one wrapper per keep-alive endpoint', () => {
    for (const fn of ['setSessionStateKeepAlive', 'listKeepaliveEvents']) {
      expect(apiSrc, `missing wrapper ${fn}`).toMatch(new RegExp(`export function ${fn}\\(`))
    }
    expect(eventsSrc).toMatch(/export function keepaliveTrailDoors\(/)
    expect(eventsSrc).toMatch(/export function listKeepaliveRuns\(/)
  })

  it('URLs and methods match the controller mappings', () => {
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id\}\/keepalive`[\s\S]{0,80}method: 'patch'/)
    expect(apiSrc).toMatch(/url: `\/session-states\/\$\{id}\/keepalive-events`[\s\S]{0,120}method: 'get'/)
    expect(eventsSrc).toMatch(/url: `\/session-states\/\$\{stateId\}\/keepalive-runs`, method: 'get'/)
    expect(eventsSrc).toContain('trailDoorsFor(`/session-states/${stateId}/keepalive-events`)')
  })

  it('row type mirrors the seven admin keep-alive columns', () => {
    for (const field of [
      'keepaliveEnabled',
      'keepaliveUrl',
      'keepaliveIntervalMinutes',
      'lastKeepaliveAt',
      'lastKeepaliveVerdict',
      'keepaliveFailStreak',
      'keepaliveStopReason',
    ]) {
      expect(apiSrc, `LoginStateRow must carry ${field}`).toContain(`${field}:`)
    }
  })

  it('payload keeps the blank-interval semantics (null = follow the global key)', () => {
    expect(apiSrc).toMatch(/intervalMinutes\?: number \| null/)
    expect(apiSrc).toMatch(/enabled: boolean/)
  })
})

describe('keep-alive ledger face', () => {
  it('registers the column family in the shared column settings source', () => {
    const cols = viewSrc.slice(viewSrc.indexOf('const LEDGER_COLUMNS'), viewSrc.indexOf('const LIGHT_FLEX_MIN'))
    for (const key of ['keepalive', 'lastKeepaliveAt', 'lastKeepaliveVerdict']) {
      expect(cols, `missing ledger column ${key}`).toContain(`key: '${key}'`)
    }
  })

  it('keeps the door open on every row and narrows only the write controls (R67 revisit entry)', () => {
    const tpl = templateOf(viewSrc)
    const door = tpl.slice(tpl.indexOf('data-test="login-state-keepalive-btn"'))
    expect(door, 'the row door itself must stay clickable on a stopped row').not.toMatch(
      /:disabled="row\.status === 0"[\s\S]{0,200}data-test="login-state-keepalive-btn"/)
    expect(tpl).toContain('data-test="login-state-keepalive-btn"')
    // The honest narrowing lives inside: switch and save follow the row state.
    expect(tpl).toContain(':disabled="kaRow !== null && kaRow.status === 0"')
    expect(tpl).toContain('data-test="login-state-keepalive-locked"')
    expect(tpl).toContain("t('sessionState.keepaliveNeedsEnabled')")
  })

  it('paused rows carry the three elements: cause verbatim + literal path + action', () => {
    const tpl = templateOf(viewSrc)
    expect(tpl).toContain('data-test="login-state-keepalive-stop"')
    expect(tpl).toContain('row.keepaliveStopReason')
    expect(tpl).toContain("t('sessionState.keepaliveFixPath')")
    expect(tpl).toContain('data-test="login-state-keepalive-reopen"')
  })

  it('replays the transition trail through the shared tail + history bases', () => {
    const tpl = templateOf(viewSrc)
    expect(tpl).toMatch(/<TaskTailDrawer[\s\S]*?:doors="kaDoors"/)
    expect(tpl).toMatch(/<RunHistoryDialog[\s\S]*?:load-runs="loadKaRuns"/)
    expect(tpl).toMatch(/:fetch-frames="fetchKaFrames"/)
    expect(tpl).toMatch(/:retention-param|retention-param="ai\.stream\.event-retention-days"/)
  })

  it('the save path owns no second range validation — core answers verbatim', () => {
    const script = scriptOf(viewSrc)
    const body = script.slice(script.indexOf('async function saveKeepAlive'))
    expect(body).toContain('setSessionStateKeepAlive')
    expect(body).toContain('bizErrorMsg(e, t(\'sessionState.keepaliveSaveFailed\'))')
    for (const literal of ['5', '1440']) {
      expect(body, `client must not re-check the interval bound ${literal}`).not.toContain(`interval ${literal}`)
      expect(body).not.toMatch(new RegExp(`interval[^\\n]*[<>=]\\s*${literal}`))
    }
  })

  it('interval input states the default and the accepted range in plain words', () => {
    const tpl = templateOf(viewSrc)
    expect(tpl).toContain('data-test="login-state-keepalive-interval"')
    expect(tpl).toContain('data-test="login-state-keepalive-interval-hint"')
    expect(zhSrc).toContain("keepaliveIntervalHint: '可填 5~1440 的整数分钟，留空按全局默认 10 分钟；间隔越短访问越频繁。'")
  })
})

describe('keep-alive locale symmetry', () => {
  const keys = [
    'keepalive', 'keepaliveColumn', 'keepaliveOn', 'keepaliveOff', 'keepalivePaused',
    'keepaliveFixPath', 'keepaliveReopen', 'keepaliveNeedsEnabled', 'keepaliveTitle',
    'keepaliveHint', 'keepaliveSwitchLabel', 'keepaliveUrlPlaceholder', 'keepaliveIntervalLabel',
    'keepaliveIntervalPlaceholder', 'keepaliveIntervalHint', 'keepaliveIntervalInvalid',
    'keepaliveSave', 'keepaliveHistory', 'keepaliveTurnedOn', 'keepaliveTurnedOff',
    'keepaliveSaveFailed', 'keepaliveTrailFailed', 'lastKeepaliveAt', 'lastKeepaliveVerdict',
    'keepaliveStreak', 'keepaliveStreakTip', 'keepaliveVerdictValid', 'keepaliveVerdictExpired',
    'keepaliveVerdictUnknown', 'keepaliveVerdictFailed',
  ]

  it('every key renders in both languages', () => {
    for (const key of keys) {
      expect(zhSrc, `zh-CN missing sessionState.${key}`).toContain(`    ${key}: `)
      expect(enSrc, `en-US missing sessionState.${key}`).toContain(`    ${key}: `)
    }
  })

  it('screen wording stays free of internal governance vocabulary', () => {
    const kaLines = (src: string) => src
      .split('\n')
      .filter((line) => /^\s+(keepalive|lastKeepalive)\w+:/.test(line))
      .join('\n')
    const banned = ['人话', '拒件', '裁决', '单源', '棘轮', '出账', '收口', 'plain-language', 'single source']
    for (const word of banned) {
      expect(kaLines(zhSrc), `zh keep-alive wording carries ${word}`).not.toContain(word)
      expect(kaLines(enSrc), `en keep-alive wording carries ${word}`).not.toContain(word)
    }
  })

  it('the retention pointer names the same settings path as the shared tail base', () => {
    expect(zhSrc).toMatch(/tailExpired: '[\s\S]*?系统设置 → 参数设置/)
    expect(zhSrc).toMatch(/keepaliveTrailFailed: '[\s\S]*?系统设置 → 参数设置/)
    expect(enSrc).toMatch(/tailExpired: '[\s\S]*?System Settings -> Parameter Settings/)
    expect(enSrc).toMatch(/keepaliveTrailFailed: '[\s\S]*?System Settings -> Parameter Settings/)
  })
})

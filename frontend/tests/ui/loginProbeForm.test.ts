/**
 * LOGIN probe form kernel contract (vitest node env, pure logic + Java cross-read).
 *
 * The monitor target wizard must be able to express the two LOGIN probe shapes the
 * core save gate accepts:
 *   form one  = credential replay  {url, credential=@cred/<key>}
 *   form two  = login-state direct read {mode=state-read, stateName, checkUrl}
 * Key names and the mode value exist only in core LoginProbe (Java); this file pins
 * the TypeScript mirror against that authority so a rename on either side fails here
 * instead of producing a payload core refuses at save time (the R25-style "looks wired,
 * actually rejected" defect).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  LOGIN_PROBE_KEYS,
  LOGIN_STATE_READ_MODE,
  buildLoginProbeConfig,
  isLoginStateReadMode,
  loginProbeGate,
  type LoginProbeFormState,
} from '@/utils/loginProbeForm'

function readRepo(rel: string): string {
  return readFileSync(fileURLToPath(new URL(`../../../${rel}`, import.meta.url)), 'utf-8')
}

const LOGIN_PROBE_JAVA = readRepo(
  'core/src/main/java/com/sixbean/testpike/core/monitor/domain/component/LoginProbe.java',
)

/** Parse a Java `public static final String NAME = "value"` constant out of the authority file. */
function javaConstant(name: string): string {
  const hit = new RegExp(`String ${name} = "([^"]*)"`).exec(LOGIN_PROBE_JAVA)
  if (!hit) throw new Error(`constant ${name} not found in LoginProbe.java (authority moved?)`)
  return hit[1]
}

const replayState: LoginProbeFormState = {
  probeMode: 'credential-replay',
  url: ' https://acme.test/login ',
  credKey: ' acme-admin ',
  stateName: '',
  checkUrl: '',
}

const stateReadState: LoginProbeFormState = {
  probeMode: LOGIN_STATE_READ_MODE,
  url: '',
  credKey: '',
  stateName: 'acme-admin-token',
  checkUrl: 'https://acme.test/api/orders',
}

describe('login probe key mirror', () => {
  it('mirrors every probe_config key name and the state-read mode from core LoginProbe', () => {
    expect(LOGIN_PROBE_KEYS.mode).toBe(javaConstant('KEY_MODE'))
    expect(LOGIN_PROBE_KEYS.url).toBe(javaConstant('KEY_URL'))
    expect(LOGIN_PROBE_KEYS.credential).toBe(javaConstant('KEY_CREDENTIAL'))
    expect(LOGIN_PROBE_KEYS.stateName).toBe(javaConstant('KEY_STATE_NAME'))
    expect(LOGIN_PROBE_KEYS.checkUrl).toBe(javaConstant('KEY_CHECK_URL'))
    expect(LOGIN_STATE_READ_MODE).toBe(javaConstant('STATE_READ_MODE'))
  })

  it('detects the direct-read form the same way core does (trim + case-insensitive)', () => {
    expect(isLoginStateReadMode('  State-Read ')).toBe(true)
    expect(isLoginStateReadMode('')).toBe(false)
    expect(isLoginStateReadMode('credential-replay')).toBe(false)
  })
})

describe('login probe gate', () => {
  it('replay form requires url + credential reference only', () => {
    const gate = loginProbeGate(replayState)
    expect(gate.required).toEqual({ url: true, cred: true, stateName: false, checkUrl: false })
    expect(gate.blockKey).toBe('')
  })

  it('direct-read form requires stateName + checkUrl only', () => {
    const gate = loginProbeGate(stateReadState)
    expect(gate.required).toEqual({ url: false, cred: false, stateName: true, checkUrl: true })
    expect(gate.blockKey).toBe('')
  })

  it('blocks with the first missing field and points at it by name', () => {
    expect(loginProbeGate({ ...stateReadState, stateName: '  ' }).blockKey)
      .toBe('monitor.wizard.needStateName')
    expect(loginProbeGate({ ...stateReadState, checkUrl: '' }).blockKey)
      .toBe('monitor.wizard.needCheckUrl')
    expect(loginProbeGate({ ...replayState, url: '' }).blockKey).toBe('monitor.wizard.needUrl')
    expect(loginProbeGate({ ...replayState, credKey: '' }).blockKey).toBe('monitor.wizard.needCred')
  })

  it('keeps the asterisk set and the blocking verdict on one source', () => {
    // A field the gate does not mark required must never be the reason it blocks.
    for (const state of [replayState, stateReadState]) {
      const gate = loginProbeGate(state)
      const blocked = gate.blockKey.replace('monitor.wizard.', '')
      if (!blocked) continue
      const field = blocked === 'needCred' ? 'cred' : blocked.replace('need', '')
      expect(gate.required[field as keyof typeof gate.required]).toBe(true)
    }
  })
})

describe('login probe payload builder', () => {
  it('replay form emits url + @cred reference and no mode key', () => {
    expect(buildLoginProbeConfig(replayState)).toEqual({
      url: 'https://acme.test/login',
      credential: '@cred/acme-admin',
    })
  })

  it('direct-read form emits mode + stateName + checkUrl and never a credential slot', () => {
    const payload = buildLoginProbeConfig({ ...stateReadState, url: 'x', credKey: 'y' })
    expect(payload).toEqual({
      mode: LOGIN_STATE_READ_MODE,
      stateName: 'acme-admin-token',
      checkUrl: 'https://acme.test/api/orders',
    })
    expect(Object.keys(payload)).not.toContain(LOGIN_PROBE_KEYS.credential)
    expect(Object.keys(payload)).not.toContain(LOGIN_PROBE_KEYS.url)
  })

  it('never carries a plaintext account or password field in either form', () => {
    for (const state of [replayState, stateReadState]) {
      const keys = Object.keys(buildLoginProbeConfig(state))
      expect(keys).not.toContain('password')
      expect(keys).not.toContain('username')
    }
  })
})

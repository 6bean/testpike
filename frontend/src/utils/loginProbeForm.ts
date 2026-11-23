/**
 * LOGIN probe form kernel — the wizard-side mirror of the core LOGIN probe shape.
 *
 * Authority = core LoginProbe (which reads probe_config) plus
 * MonitorTargetServiceImpl.validateLoginCredentialRef (which refuses an unsavable
 * shape at save time). This file only mirrors the key names, the mode value and the
 * required-field verdict so the wizard can block a shape core would reject instead of
 * letting the user discover it in step 4. The key names are asserted equal to the
 * Java constants by tests/ui/loginProbeForm.test.ts, so a rename on either side fails
 * that contract rather than producing a payload the probe silently ignores.
 *
 * Two accepted forms:
 *   form one  credential replay     {url, credential: "@cred/<key>"}   — mode omitted
 *   form two  login-state read      {mode: "state-read", stateName, checkUrl}
 * Form two rides an already captured ledger row as a request header, so it needs no
 * url/credential pair and never sees the secret value.
 */

/** probe_config key names, mirrored from LoginProbe.KEY_* (contract test pins them). */
export const LOGIN_PROBE_KEYS = {
  mode: 'mode',
  url: 'url',
  credential: 'credential',
  stateName: 'stateName',
  checkUrl: 'checkUrl',
} as const

/** Mirror of LoginProbe.STATE_READ_MODE — the only mode value core treats as direct read. */
export const LOGIN_STATE_READ_MODE = 'state-read'

/** UI radio value for the replay form. Never sent: an absent mode already means replay. */
export const LOGIN_REPLAY_MODE = 'credential-replay'

/** Wizard fields the two forms are built from. */
export interface LoginProbeFormState {
  probeMode: string
  url: string
  credKey: string
  stateName: string
  checkUrl: string
}

/** Fields that can carry a required asterisk in step 2 of the LOGIN lane. */
export type LoginProbeField = 'url' | 'cred' | 'stateName' | 'checkUrl'

export interface LoginProbeGate {
  required: Record<LoginProbeField, boolean>
  /** i18n key of the first missing field; empty string = the shape is complete. */
  blockKey: string
}

/** Same posture as LoginProbe.isStateReadMode: trim, then ignore case. */
export function isLoginStateReadMode(mode: string): boolean {
  return (mode ?? '').trim().toLowerCase() === LOGIN_STATE_READ_MODE
}

/**
 * Required-field set plus the blocking reason, computed in one pass so the asterisks
 * and the guard can never drift apart (a star on a field the guard ignores, or vice
 * versa, is the "control looks global but is not" defect).
 */
export function loginProbeGate(state: LoginProbeFormState): LoginProbeGate {
  if (isLoginStateReadMode(state.probeMode)) {
    return {
      required: { url: false, cred: false, stateName: true, checkUrl: true },
      blockKey: !state.stateName.trim()
        ? 'monitor.wizard.needStateName'
        : (!state.checkUrl.trim() ? 'monitor.wizard.needCheckUrl' : ''),
    }
  }
  return {
    required: { url: true, cred: true, stateName: false, checkUrl: false },
    blockKey: !state.url.trim()
      ? 'monitor.wizard.needUrl'
      : (!state.credKey.trim() ? 'monitor.wizard.needCred' : ''),
  }
}

/** probe_config for the selected form. Values are trimmed; secrets never appear here. */
export function buildLoginProbeConfig(state: LoginProbeFormState): Record<string, unknown> {
  if (isLoginStateReadMode(state.probeMode)) {
    return {
      [LOGIN_PROBE_KEYS.mode]: LOGIN_STATE_READ_MODE,
      [LOGIN_PROBE_KEYS.stateName]: state.stateName.trim(),
      [LOGIN_PROBE_KEYS.checkUrl]: state.checkUrl.trim(),
    }
  }
  return {
    [LOGIN_PROBE_KEYS.url]: state.url.trim(),
    [LOGIN_PROBE_KEYS.credential]: `@cred/${state.credKey.trim()}`,
  }
}

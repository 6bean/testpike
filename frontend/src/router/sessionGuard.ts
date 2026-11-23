import { isNetworkLayerError } from '@/api/request'

/**
 * Route guard (session restore + RBAC + password-expiry sticky), extracted
 * from router/index.ts so the whole decision table is testable at the node
 * L2 layer (no DOM, injected deps) while index.ts stays the single wiring
 * point for the real token/store/toast/i18n deps.
 *
 * Why this extraction exists (product defect, tear evidence):
 * the previous inline guard caught EVERY loadMe failure and treated it as
 * an expired session — logout + redirect to /login. When the backend was
 * only transiently unreachable (network-layer BizError, no HTTP response:
 * ERR_EMPTY_RESPONSE / connection refused / timeout) users were force-
 * logged-out mid-stream. A network blip is not an auth failure: the session
 * is kept, the restore is retried once, and if the backend is still down
 * the user gets a human-readable toast and the navigation is honestly held
 * (return false) instead of the token being destroyed.
 */

/** segment of the auth store the guard consumes (pinia store satisfies it directly). */
export interface GuardAuthLike {
  user: unknown
  passwordExpired: boolean
  hasPerm(code: string): boolean
  loadMe(): Promise<void>
  logout(): void
}

/** Structural view of vue-router's `to` used by the guard (kept DOM-free). */
export interface GuardRoute {
  name?: string | symbol
  path: string
  fullPath: string
  meta?: { public?: boolean; perm?: string }
}

/** What beforeEach may return: continue / abort / redirect target (query narrowed to satisfy vue-router's LocationQueryRaw). */
export type GuardDecision = boolean | { path: string; query?: Record<string, string> }

export interface AuthGuardDeps {
  hasToken(): boolean
  getAuth(): GuardAuthLike
  error(message: string): void
  warning(message: string): void
  t(key: string, named?: Record<string, unknown>): string
  /** Delay before the single network-failure retry (ms); overridable in tests. */
  retryDelayMs?: number
}

type RestoreOutcome = 'ok' | 'network' | 'auth-lost'

async function restoreOnce(auth: GuardAuthLike): Promise<RestoreOutcome> {
  try {
    await auth.loadMe()
    return 'ok'
  } catch (e) {
    return isNetworkLayerError(e) ? 'network' : 'auth-lost'
  }
}

/** Default retry wait: long enough for a fast service re-exec, short enough to feel instant. */
const DEFAULT_RETRY_DELAY_MS = 1500

export function createAuthGuard(deps: AuthGuardDeps): (to: GuardRoute) => Promise<GuardDecision> {
  return async (to) => {
    const hasToken = deps.hasToken()
    if (to.meta?.public) {
      if (hasToken && to.name === 'Login') {
        return { path: '/dashboard' }
      }
      return true
    }
    if (!hasToken) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    // Restore user info on refresh before releasing the navigation (race-free)
    const auth = deps.getAuth()
    if (!auth.user) {
      let outcome = await restoreOnce(auth)
      if (outcome === 'network') {
        await new Promise((resolve) => setTimeout(resolve, deps.retryDelayMs ?? DEFAULT_RETRY_DELAY_MS))
        outcome = await restoreOnce(auth)
      }
      if (outcome === 'network') {
        // Transient backend unreachability: keep the session, tell the user
        // plainly, hold this navigation — a refresh retries once it is back.
        deps.error(deps.t('http.networkError'))
        return false
      }
      if (outcome === 'auth-lost') {
        auth.logout()
        return { path: '/login' }
      }
    }
    // R5: expired-password sessions stay pinned to the forced change page
    if (auth.passwordExpired && to.name !== 'PwdExpired') {
      return { path: '/password-expired' }
    }
    // K4 RBAC: missing feature permission → human warning + back to dashboard (backend 403 still guards)
    if (to.meta?.perm && !auth.hasPerm(to.meta.perm)) {
      deps.warning(deps.t('layout.permDenied', { perm: to.meta.perm }))
      return { path: '/dashboard' }
    }
    return true
  }
}

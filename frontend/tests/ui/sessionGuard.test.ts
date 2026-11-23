/**
 * Route-guard session-restore branch (product defect: transient backend
 * unreachability must NOT be mistaken for an expired auth session).
 *
 * Contract: when the guard restores user info (auth.loadMe) and the failure
 * is a network-layer error (BizError code NETWORK, i.e. no HTTP response at
 * all — ERR_EMPTY_RESPONSE / connection refused / timeout), the session is
 * kept: no logout, no redirect to /login; the user gets a human-readable
 * error toast and the navigation is held (return false) so a retry (page
 * refresh or next navigation) can succeed once the backend is back.
 * Only a real authentication failure (401/403 semantics via BizError)
 * clears state and sends the user to the login page.
 *
 * The guard body lives in src/router/sessionGuard.ts as a dependency-
 * injected factory; src/router/index.ts wires the real deps. This keeps a
 * single implementation reachable by pure node-layer L2 (no DOM needed).
 */
import { describe, expect, it, vi } from 'vitest'
import { BizError, NETWORK_ERROR_CODE } from '@/api/request'
import { createAuthGuard, type GuardAuthLike, type GuardRoute } from '@/router/sessionGuard'

function route(partial: Partial<GuardRoute> = {}): GuardRoute {
  return { name: 'Cases', path: '/cases', fullPath: '/cases', meta: {}, ...partial }
}

interface Harness {
  auth: GuardAuthLike & { loadMe: ReturnType<typeof vi.fn>; logout: ReturnType<typeof vi.fn> }
  errors: string[]
  warnings: string[]
  guard: ReturnType<typeof createAuthGuard>
}

function makeHarness(restoreImpl: () => Promise<void>, over: Partial<GuardAuthLike> = {}): Harness {
  const restore = vi.fn(restoreImpl)
  const clear = vi.fn()
  const errors: string[] = []
  const warnings: string[] = []
  const auth = {
    user: null,
    passwordExpired: false,
    hasPerm: () => true,
    loadMe: restore,
    logout: clear,
    ...over,
  } as unknown as Harness['auth']
  const guard = createAuthGuard({
    hasToken: () => true,
    getAuth: () => auth as GuardAuthLike,
    error: (m) => errors.push(m),
    warning: (m) => warnings.push(m),
    t: (key, named) => `${key}:${String((named as { perm?: string })?.perm ?? '')}`,
  })
  return { auth, errors, warnings, guard }
}

describe('router session guard — network failure keeps the session', () => {
  it('holds navigation and warns without clearing state when loadMe hits a network-layer error', async () => {
    const h = makeHarness(() => Promise.reject(new BizError(NETWORK_ERROR_CODE, 'network down')))
    const decision = await h.guard(route())
    expect(decision).toBe(false)
    expect(h.auth.logout).not.toHaveBeenCalled()
    expect(h.errors).toEqual(['http.networkError:'])
  })

  it('retries the restore once before giving up on the first network failure', async () => {
    const restore = vi
      .fn()
      .mockRejectedValueOnce(new BizError(NETWORK_ERROR_CODE, 'transient'))
      .mockResolvedValueOnce(undefined)
    const auth = {
      user: null,
      passwordExpired: false,
      hasPerm: () => true,
      loadMe: restore,
      logout: vi.fn(),
    } as unknown as GuardAuthLike
    const guard = createAuthGuard({
      hasToken: () => true,
      getAuth: () => auth,
      error: () => undefined,
      warning: () => undefined,
      t: (key) => key,
    })
    const decision = await guard(route())
    expect(decision).toBe(true)
    expect(restore).toHaveBeenCalledTimes(2)
  })

  it('still clears state and goes to login on a real auth failure (401 BizError)', async () => {
    const h = makeHarness(() => Promise.reject(new BizError('401', 'session expired')))
    const decision = await h.guard(route())
    expect(decision).toEqual({ path: '/login' })
    expect(h.auth.logout).toHaveBeenCalledTimes(1)
    expect(h.errors).toEqual([])
  })

  it('treats business-code rejections (HTTP answered) as auth failures, not network blips', async () => {
    const h = makeHarness(() => Promise.reject(new BizError('40001', 'biz gate')))
    const decision = await h.guard(route())
    expect(decision).toEqual({ path: '/login' })
    expect(h.auth.logout).toHaveBeenCalledTimes(1)
  })

  it('keeps the conservative clear-on-reject behaviour for unexpected non-BizError failures', async () => {
    const h = makeHarness(() => Promise.reject(new Error('boom')))
    const decision = await h.guard(route())
    expect(decision).toEqual({ path: '/login' })
    expect(h.auth.logout).toHaveBeenCalledTimes(1)
  })

  it('passes straight through once the session is restored', async () => {
    const h = makeHarness(() => Promise.resolve())
    const decision = await h.guard(route())
    expect(decision).toBe(true)
    expect(h.auth.logout).not.toHaveBeenCalled()
    expect(h.errors).toEqual([])
  })

  it('skips the restore entirely when user info is already loaded', async () => {
    const h = makeHarness(() => Promise.reject(new Error('must not be called')), { user: {} })
    const decision = await h.guard(route())
    expect(decision).toBe(true)
    expect(h.auth.loadMe).not.toHaveBeenCalled()
  })
})

describe('router session guard — existing navigation contract preserved', () => {
  it('redirects unauthenticated requests to login carrying the redirect target', async () => {
    const h = makeHarness(() => Promise.resolve())
    const deps = {
      hasToken: () => false,
      getAuth: h.auth,
      error: () => undefined,
      warning: () => undefined,
      t: (k: string) => k,
    }
    const decision = await createAuthGuard(deps)(route({ fullPath: '/cases?x=1' }))
    expect(decision).toEqual({ path: '/login', query: { redirect: '/cases?x=1' } })
  })

  it('sends a logged-in visitor of the login page to the dashboard', async () => {
    const h = makeHarness(() => Promise.resolve())
    const decision = await h.guard(route({ name: 'Login', path: '/login', fullPath: '/login', meta: { public: true } }))
    expect(decision).toEqual({ path: '/dashboard' })
  })

  it('lets other public pages through without touching the session', async () => {
    const h = makeHarness(() => Promise.reject(new Error('must not be called')))
    const decision = await h.guard(route({ meta: { public: true }, fullPath: '/share/x' }))
    expect(decision).toBe(true)
    expect(h.auth.loadMe).not.toHaveBeenCalled()
  })

  it('pins an expired-password session to the forced change-password page', async () => {
    const h = makeHarness(() => Promise.resolve(), { passwordExpired: true })
    const decision = await h.guard(route({ name: 'Cases' }))
    expect(decision).toEqual({ path: '/password-expired' })
  })

  it('lets the change-password page itself through despite the expired flag', async () => {
    const h = makeHarness(() => Promise.resolve(), { passwordExpired: true })
    const decision = await h.guard(route({ name: 'PwdExpired', path: '/password-expired', fullPath: '/password-expired' }))
    expect(decision).toBe(true)
  })

  it('denies a missing-permission target with a warning and bounces to the dashboard', async () => {
    const h = makeHarness(() => Promise.resolve(), { hasPerm: () => false })
    const decision = await h.guard(route({ meta: { perm: 'case:edit' } }))
    expect(decision).toEqual({ path: '/dashboard' })
    expect(h.warnings).toEqual(['layout.permDenied:case:edit'])
  })
})

/**
 * Tail SSE road token renewal (product gap from the WT window r19 attribution):
 * the live-tail pump opens the SSE door with a bare fetch, so it physically
 * bypasses the axios response-interceptor path (401 -> single-flight refresh
 * -> replay). A session older than auth.access-ttl-seconds therefore always
 * produces a user-visible 401 on every stream (re)open and the drawer can no
 * longer return to the stream road.
 *
 * Layer 1 of the contract — the renewal helpers themselves, judged against the
 * REAL request.ts (no second refresh implementation, R40):
 *   - ensureFreshAccessToken: proactive renewal for non-axios doors. The
 *     access token is a JWT; when its exp lands within the lead window the
 *     SAME single-flight refresh (R6) is awaited BEFORE the door opens, so
 *     a healthy session never puts a 401 on the wire.
 *   - renewAccessTokenOnce: the 401 safety net, delegating to tryRefresh.
 * Layer 2 (call-shape wiring of openEventTail) is in tailSseRenewalWiring.test.ts.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'

const store = new Map<string, string>()
;(globalThis as Record<string, unknown>).localStorage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, String(v)),
  removeItem: (k: string) => void store.delete(k),
  clear: () => store.clear(),
}

/** A structurally valid JWT (unsigned, exp claim in seconds), base64url. */
function fakeJwt(expSec: number): string {
  const b64url = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url({ sub: '1', exp: expSec })}.sig`
}

describe('request.ts 非 axios 门的续期单源（JWT exp 事前续 + 401 兜底）', () => {
  let axios: typeof import('axios').default
  beforeEach(async () => {
    store.clear()
    axios = (await import('axios')).default
    vi.restoreAllMocks()
  })

  it('exp 在提前量内：await 单飞刷新并返回轮换后的新 token，头里不再带过期值', async () => {
    const { setToken, ensureFreshAccessToken, getToken } = await import('@/api/request')
    setToken(fakeJwt(Math.floor(Date.now() / 1000) + 30), 'Bearer', 'RT-1')
    const post = vi.spyOn(axios, 'post').mockResolvedValue({
      data: { code: '0', msg: '', data: { accessToken: 'FRESH', tokenType: 'Bearer', refreshToken: 'RT-2' } },
    } as never)
    const token = await ensureFreshAccessToken()
    expect(post).toHaveBeenCalledTimes(1)
    expect(String(post.mock.calls[0][0])).toContain('/auth/refresh')
    expect(token).toBe('FRESH')
    expect(getToken()).toBe('FRESH')
    expect(store.get('tp_refresh_token')).toBe('RT-2')
  })

  it('exp 尚远：零刷新请求，原 token 直通（不为每次开窗多烧一次 refresh）', async () => {
    const { setToken, ensureFreshAccessToken } = await import('@/api/request')
    setToken(fakeJwt(Math.floor(Date.now() / 1000) + 3600), 'Bearer', 'RT-1')
    const post = vi.spyOn(axios, 'post')
    await expect(ensureFreshAccessToken()).resolves.toBe(store.get('tp_access_token'))
    expect(post).not.toHaveBeenCalled()
  })

  it('无 refresh token / 刷新失败：保守返回原 token，绝不静默清态跳登录', async () => {
    const { setToken, ensureFreshAccessToken, renewAccessTokenOnce, getToken } = await import('@/api/request')
    const near = fakeJwt(Math.floor(Date.now() / 1000) + 10)
    setToken(near, 'Bearer')
    const post = vi.spyOn(axios, 'post')
    await expect(ensureFreshAccessToken()).resolves.toBe(near)
    expect(post).not.toHaveBeenCalled()
    await expect(renewAccessTokenOnce()).resolves.toBe(false)
    expect(getToken()).toBe(near)
    setToken(near, 'Bearer', 'RT-1')
    vi.spyOn(axios, 'post').mockResolvedValue({
      data: { code: '40101', msg: 'refresh revoked', data: null },
    } as never)
    await expect(renewAccessTokenOnce()).resolves.toBe(false)
    expect(getToken()).toBe(near)
  })

  it('非 JWT 形态 token 容错：exp 读不出即视为不可判，零刷新零异常', async () => {
    const { setToken, ensureFreshAccessToken, decodeJwtExpMs } = await import('@/api/request')
    setToken('opaque-session-token', 'Bearer', 'RT-1')
    const post = vi.spyOn(axios, 'post')
    await expect(ensureFreshAccessToken()).resolves.toBe('opaque-session-token')
    expect(post).not.toHaveBeenCalled()
    expect(decodeJwtExpMs('not.a.jwt')).toBeNull()
    expect(decodeJwtExpMs('')).toBeNull()
  })
})

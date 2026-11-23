/**
 * Transient-vs-dead renewal split (defect ticket , WT window r21).
 *
 * The single-flight refresh used to fold "the refresh POST was swallowed by a
 * network churn" into the same `false` as "the server revoked this session"
 * (P18: an examiner collapsing its own failure into a negative answer). Eight
 * concurrent tails then each opened the SSE door with the stale near-expiry
 * token and answered the churn with 8x401 on the wire.
 *
 * Contract (bidirectional fixtures):
 * - churn / no-verdict failure (no HTTP response, or 5xx from the refresh
 * endpoint itself) => outcome 'transient': the door must NEVER be opened
 * with the stale token (zero 401 on the wire), session state stays intact
 * (no clear, no login redirect), and the tail degrades to the poll cadence
 * which retries by itself;
 * - explicit rejection (HTTP 4xx / business code != 0) => outcome false:
 * the existing roads keep their exact semantics (interceptor clears and
 * redirects; ensureFreshAccessToken hands back the old token for the
 * caller's own degrade path).
 *
 * Layer 1 (this file) runs against the REAL request.ts (no second refresh
 * implementation, R40); the door call-shape half lives in the sibling
 * tailSseTransientRenewalWiring.test.ts, mirroring tailSseRenewalWiring.
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

/** Network-layer failure shape: axios rejection WITHOUT any HTTP response. */
function churnError(): Error {
  return Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK_CHANGED' })
}

describe('request.ts 续期三态（churn=transient 禁交旧 token / 拒件=false 零漂移）', () => {
  let axios: typeof import('axios').default
  beforeEach(async () => {
    store.clear()
    axios = (await import('axios')).default
    vi.restoreAllMocks()
  })

  it('refresh 被网络抖动吞掉（无响应体）：renewAccessTokenOnce 产 transient，会话态零清', async () => {
    const { setToken, renewAccessTokenOnce, getToken } = await import('@/api/request')
    const near = fakeJwt(Math.floor(Date.now() / 1000) + 10)
    setToken(near, 'Bearer', 'RT-1')
    vi.spyOn(axios, 'post').mockRejectedValue(churnError())
    await expect(renewAccessTokenOnce()).resolves.toBe('transient')
    // churn is not a session verdict: tokens must survive untouched
    expect(getToken()).toBe(near)
    expect(store.get('tp_refresh_token')).toBe('RT-1')
  })

  it('refresh 端点自身 5xx（无判定权威）：同样 transient，禁清态', async () => {
    const { setToken, renewAccessTokenOnce, getToken } = await import('@/api/request')
    const near = fakeJwt(Math.floor(Date.now() / 1000) + 10)
    setToken(near, 'Bearer', 'RT-1')
    vi.spyOn(axios, 'post').mockRejectedValue(
      Object.assign(new Error('Bad Gateway'), { code: 'ERR_BAD_RESPONSE', response: { status: 502 } }),
    )
    await expect(renewAccessTokenOnce()).resolves.toBe('transient')
    expect(getToken()).toBe(near)
  })

  it('refresh 被服务端明确拒绝（401 响应）：false，token 原样留给调用方降级路（零漂移）', async () => {
    const { setToken, renewAccessTokenOnce, getToken } = await import('@/api/request')
    const near = fakeJwt(Math.floor(Date.now() / 1000) + 10)
    setToken(near, 'Bearer', 'RT-1')
    vi.spyOn(axios, 'post').mockRejectedValue(
      Object.assign(new Error('Unauthorized'), { code: 'ERR_BAD_REQUEST', response: { status: 401 } }),
    )
    await expect(renewAccessTokenOnce()).resolves.toBe(false)
    expect(getToken()).toBe(near)
  })

  it('exp 在提前量内且 refresh 撞网络抖动：ensureFreshAccessToken 禁交旧 token（抛，非返回值）', async () => {
    const { setToken, ensureFreshAccessToken, getToken } = await import('@/api/request')
    const near = fakeJwt(Math.floor(Date.now() / 1000) + 10)
    setToken(near, 'Bearer', 'RT-1')
    vi.spyOn(axios, 'post').mockRejectedValue(churnError())
    await expect(ensureFreshAccessToken()).rejects.toThrow()
    expect(getToken()).toBe(near)
  })

  it('exp 在提前量内且 refresh 被明确拒绝：保守返回原 token（既有降级路语义零漂移）', async () => {
    const { setToken, ensureFreshAccessToken } = await import('@/api/request')
    const near = fakeJwt(Math.floor(Date.now() / 1000) + 10)
    setToken(near, 'Bearer', 'RT-1')
    vi.spyOn(axios, 'post').mockResolvedValue({
      data: { code: '40101', msg: 'refresh revoked', data: null },
    } as never)
    await expect(ensureFreshAccessToken()).resolves.toBe(near)
  })

  it('单飞共享：并发两路撞抖动只发一条 refresh POST', async () => {
    const { setToken, renewAccessTokenOnce } = await import('@/api/request')
    setToken(fakeJwt(Math.floor(Date.now() / 1000) + 10), 'Bearer', 'RT-1')
    const post = vi.spyOn(axios, 'post').mockRejectedValue(churnError())
    const both = await Promise.all([renewAccessTokenOnce(), renewAccessTokenOnce()])
    expect(both).toEqual(['transient', 'transient'])
    expect(post).toHaveBeenCalledTimes(1)
  })
})

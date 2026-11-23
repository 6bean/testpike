import { ht } from '../utils/dictLabel'
import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { showGuidedError } from '../utils/errorNotify'


export interface ApiResponse<T = unknown> {
  code: string
  msg: string
  data: T
  success?: boolean
}


export interface PageResult<T> {
  list: T[]
  total: number
  page?: number
  size?: number
}

const TOKEN_KEY = 'tp_access_token'
const REFRESH_KEY = 'tp_refresh_token'
const TOKEN_TYPE_KEY = 'tp_token_type'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}


export function getRefreshToken(): string {
  return localStorage.getItem(REFRESH_KEY) || ''
}


export function setToken(token: string, tokenType = 'Bearer', refreshToken?: string | null): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(TOKEN_TYPE_KEY, tokenType)
  if (refreshToken !== undefined) {
    if (refreshToken) {
      localStorage.setItem(REFRESH_KEY, refreshToken)
    } else {
      localStorage.removeItem(REFRESH_KEY)
    }
  }
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(TOKEN_TYPE_KEY)
  localStorage.removeItem(REFRESH_KEY)
}


export class BizError extends Error {
  code: string
  constructor(code: string, msg: string) {
    super(msg)
    this.code = code
  }
}

/**
 * Fallback error code for pure network-layer failures (no HTTP response at
 * all: ERR_EMPTY_RESPONSE / connection refused / timeout). The response
 * interceptor stamps it whenever neither a business code nor an HTTP status
 * is present, so consumers can tell "backend momentarily unreachable" apart
 * from real auth/business rejections. Single source: route guard uses
 * isNetworkLayerError, never re-derives it from the message text.
 */
export const NETWORK_ERROR_CODE = 'NETWORK'

export function isNetworkLayerError(e: unknown): boolean {
  return e instanceof BizError && e.code === NETWORK_ERROR_CODE
}

// Auth is a Bearer token from localStorage — the cookie-based XSRF flow is
// never used. Clearing the defaults stops axios from reading document.cookie
// on every request, which Chromium flags as an Audits performance issue
// (DocumentCookie). Shared by the instance and the bare refresh call below.
const NO_XSRF = { xsrfCookieName: '', xsrfHeaderName: '' }

const instance: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  ...NO_XSRF,
})

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 *
 * Three-state verdict (defect ticket — the WT r21 storm folded a
 * swallowed refresh into the same `false` as a revoked session, so eight
 * concurrent tails each opened the SSE door with the stale token and put
 * 8x401 on the wire): `true` = rotated; `'transient'` = NO authoritative
 * verdict (no HTTP response arrived, or the refresh endpoint itself 5xx'd)
 * — churn is not session death, callers must keep state and retry later;
 * `false` = explicit rejection (4xx / business code != 0) or no refresh
 * material — the existing clear-and-redirect roads apply unchanged.
 */
let refreshPromise: Promise<boolean | 'transient'> | null = null

/** True when the refresh failure carries no server verdict (see tryRefresh). */
function isTransientRefreshFailure(err: unknown): boolean {
  const status = (err as { response?: { status?: number } })?.response?.status
  if (status === undefined || status === null) return true
  return Number.isFinite(status) && status >= 500
}

function tryRefresh(): Promise<boolean | 'transient'> {
  if (!refreshPromise) {
    const refreshToken = getRefreshToken()
    if (!refreshToken) {
      return Promise.resolve(false)
    }
    refreshPromise = axios
      .post<ApiResponse<{ accessToken: string; tokenType: string; refreshToken?: string | null }>>(
        '/api/v1/auth/refresh',
        { refreshToken },
        { timeout: 10000, ...NO_XSRF },
      )
      .then((resp) => {
        const body = resp.data
        if (body?.code === '0' && body.data?.accessToken) {
          setToken(body.data.accessToken, body.data.tokenType || 'Bearer', body.data.refreshToken ?? null)
          import('@/stores/auth')
            .then((m) => m.useAuthStore().loadPerms())
            .catch(() => undefined)
          return true
        }
        return false
      })
      .catch((err: unknown) => (isTransientRefreshFailure(err) ? 'transient' : false))
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

/** Lead window for the proactive renewal of non-axios doors (SSE / bare fetch). */
const RENEW_LEAD_MS = 60_000

/**
 * Read the JWT `exp` claim (milliseconds since epoch) from an access token.
 * Returns null for opaque/legacy token shapes — callers must treat null as
 * "cannot judge", never as "expired" (no refresh storm on unreadable tokens).
 */
export function decodeJwtExpMs(token: string): number | null {
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const json = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
    const exp = (JSON.parse(json) as { exp?: unknown }).exp
    return typeof exp === 'number' && Number.isFinite(exp) ? exp * 1000 : null
  } catch {
    return null
  }
}

/**
 * Proactive renewal for doors that bypass the axios response interceptor
 * (the live-tail SSE road opens with a bare fetch, so the R6 401 -> refresh ->
 * replay path can never cover it). Awaited BEFORE opening such a door: when
 * the access token's exp lands within the lead window, the SAME single-flight
 * refresh is awaited, so a healthy session never puts a 401 on the wire.
 * On an EXPLICIT rejection the current token is returned unchanged — the
 * caller still has its own degrade road (poll fallback), and a dead session
 * is the interceptor's job to retire, not this helper's. On a 'transient'
 * verdict (churn, refresh endpoint 5xx) the stale token is REFUSED: handing
 * it over is exactly how a storm burst answered with 8x401 , so
 * the caller must back off and retry instead of knocking with a token the
 * server could not rotate in time.
 */
export async function ensureFreshAccessToken(): Promise<string> {
  const token = getToken()
  if (!token) return token
  const expMs = decodeJwtExpMs(token)
  if (expMs !== null && expMs - Date.now() < RENEW_LEAD_MS) {
    const outcome = await tryRefresh()
    if (outcome === true) return getToken()
    if (outcome === 'transient') {
      throw new BizError(NETWORK_ERROR_CODE, ht('http.networkError'))
    }
  }
  return token
}

/**
 * Safety net for non-axios doors that still saw a 401 (revocation, clock
 * skew): delegate to the R6 single-flight refresh so concurrent callers share
 * one refresh request. NEVER re-implement the refresh round-trip elsewhere.
 * Outcome semantics per tryRefresh (transient = keep state, do NOT reopen
 * with the current token).
 */
export function renewAccessTokenOnce(): Promise<boolean | 'transient'> {
  return tryRefresh()
}

function gotoLogin(): void {
  clearToken()
  if (window.location.hash !== '#/login') {
    window.location.hash = '#/login'
  }
}

instance.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const body = response.data
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return response
    }
    if (body.code === '0') {
      return response
    }
    if (body.code === '403' || body.code === '40301') {
      gotoLogin()
      showGuidedError(body.msg || ht('http.sessionExpired'), ht('components.nextStep'))
    }
    throw new BizError(body.code, body.msg || ht('http.requestFailed'))
  },
  async (error) => {
    const config = error?.config as (AxiosRequestConfig & { _tpRetry?: boolean }) | undefined
    const reqUrl = String(config?.url || '')
    if (error?.response?.status === 401 && config && !config._tpRetry && !reqUrl.includes('/auth/')) {
      const outcome = await tryRefresh()
      if (outcome === true) {
        config._tpRetry = true
        return instance.request(config)
      }
      if (outcome === 'transient') {
        // No server verdict — churn, not session death. Keep the session and
        // surface a network error; retry roads (poll cadence / user action)
        // decide when to knock again. Clearing state here would log users out
        // every time the NIC flaps .
        throw new BizError(NETWORK_ERROR_CODE, ht('http.networkError'))
      }
      const msg = (error?.response?.data as ApiResponse | undefined)?.msg || ht('http.sessionExpired')
      gotoLogin()
      showGuidedError(msg, ht('components.nextStep'))
      throw new BizError('401', msg)
    }
    const status = error?.response?.status
    const data = error?.response?.data
    let msg = ''
    let bizCode = ''
    if (data && typeof data === 'object') {
      msg = data.msg || data.message || ''
      bizCode = data.code || ''
    }
    if (status === 428) {
      const expiredMsg = msg || ht('http.passwordExpired')
      if (window.location.hash !== '#/password-expired') {
        window.location.hash = '#/password-expired'
      }
      showGuidedError(expiredMsg, ht('components.nextStep'), 'warning')
      throw new BizError('428', expiredMsg)
    }
    if (!msg) {
      if (status === 401) msg = ht('http.sessionExpired')
      else if (status === 403) msg = ht('http.forbidden')
      else if (status === 404) msg = ht('http.notFound')
      else if (status === 409) msg = ht('http.conflict')
      else if (status >= 500) msg = ht('http.serverError')
      else if (error?.code === 'ECONNABORTED') msg = ht('http.timeout')
      else msg = ht('http.networkError')
    }
    if (status === 401) {
      gotoLogin()
    }
    // No business code and no HTTP status at all = network-layer failure (NETWORK_ERROR_CODE).
    throw new BizError(String(bizCode || status || NETWORK_ERROR_CODE), msg)
  },
)


export type ProgressFn = (percent: number) => void


export function postMultipart<T = unknown>(
  url: string,
  file: File,
  extra?: Record<string, string>,
  onProgress?: ProgressFn,
  timeout = 60000,
): Promise<T> {
  const fd = new FormData()
  fd.append('file', file)
  if (extra) {
    for (const [k, v] of Object.entries(extra)) {
      fd.append(k, v)
    }
  }
  return request<T>({
    url,
    method: 'post',
    data: fd,
    timeout,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}


export async function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  const resp = await instance.request<ApiResponse<T>>(config)
  const body = resp.data
  if (body && typeof body === 'object' && 'code' in body && body.code === '0') {
    return body.data as T
  }
  return body as unknown as T
}

export default instance

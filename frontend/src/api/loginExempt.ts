import { request } from './request'

/**
 * Login-exemption admin face (AV5) — mirror of /api/v1/login-exempt.
 * Exactly three doors: read one environment's declaration, declare/overwrite
 * it, record a re-check observation. The page never decides verdicts: form
 * rules, vault cross-checks and the re-check verdict table are single-sourced
 * in core, and no payload here can carry a secret — the master code is only
 * ever referenced by its vault key (red lines 1/3/6).
 */

/** Declaration read model (admin LoginExemptView record mirror). */
export interface LoginExemptView {
  envId: number
  projectId: number
  /** false = honest undeclared, not an affirmative exemption. */
  declared: boolean
  /** none / master_code / whitelist_accounts */
  kind: string | null
  /** Vault reference key for the master code — a key, never a value. */
  credKey: string | null
  accounts: string[] | null
  /** trial_login / manual */
  reviewMethod: string | null
  /** pending / passed / failed */
  reviewStatus: string | null
  /** Server wording of the last re-check verdict (echoed, not page-made). */
  reviewDetail: string | null
  reviewedAt: string | null
}

/** Declaration payload (admin LoginExemptDeclareRequest mirror). */
export interface LoginExemptDeclarePayload {
  kind: string
  credKey?: string | null
  accounts?: string[] | null
  reviewMethod: string
}

/** Re-check payload (admin LoginExemptReviewRequest mirror). reviewedAt is a server fact and never sent. */
export interface LoginExemptReviewPayload {
  signal: string
  note?: string | null
}

export function getLoginExempt(envId: number): Promise<LoginExemptView> {
  return request<LoginExemptView>({ url: `/login-exempt/${envId}`, method: 'get' })
}

export function declareLoginExempt(
  envId: number,
  payload: LoginExemptDeclarePayload,
): Promise<LoginExemptView> {
  return request<LoginExemptView>({ url: `/login-exempt/${envId}`, method: 'put', data: payload })
}

export function reviewLoginExempt(
  envId: number,
  payload: LoginExemptReviewPayload,
): Promise<LoginExemptView> {
  return request<LoginExemptView>({
    url: `/login-exempt/${envId}/review`,
    method: 'post',
    data: payload,
  })
}

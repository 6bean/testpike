import { request } from './request'




export interface UsageCalibrationView {
  model: string
  provider: string
  sampleCount: number
  avgDeviationPct: number | null
  p95AbsDeviationPct: number | null
  avgEstTokens: number | null
  avgUsageTokens: number | null
  lastSeenAt: string | null
}


export interface UsageSampleView {
  id: number
  model: string
  provider: string
  chain: string
  projectId: number | null
  estTokens: number
  usagePromptTokens: number
  deviationRate: number | null
  latencyMs: number
  createdAt: string
  /** E9 cache reading: prompt tokens served from provider cache (0 = no hit). */
  cachedTokens: number
}

export interface UsageSamplePage {
  list: UsageSampleView[]
  total: number
}


export function getLlmUsageCalibration(days = 7) {
  return request<UsageCalibrationView[]>({
    url: '/llm-usage/calibration',
    method: 'get',
    params: { days },
  })
}

/**
 * Optional server-side sort pair forwarded to the core column whitelist
 * (blank/undefined keeps the historical id DESC newest-first order). */
export function getLlmUsageSamples(params: {
  model?: string
  chain?: string
  keyword?: string
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}) {
  return request<UsageSamplePage>({ url: '/llm-usage/samples', method: 'get', params })
}

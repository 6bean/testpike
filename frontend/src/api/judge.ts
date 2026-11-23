import { request } from './request'




export interface RunScorecard {
  runId: number
  rubricName: string
  rubricDomain: string
  rubricVersion: number | null
  evalsetName: string
  evalsetVersion: number | null
  
  status: number | null
  passScore: number | null
  sampleTotal: number | null
  scored: number | null
  avgFinalScore: number | null
  passCount: number | null
  passRate: number | null
  tokensTotal: number | null
  latencyAvgMs: number | null
  
  dimensionAverages: Record<string, number>
}


export interface RunSampleCard {
  recordId: number | null
  sampleId: number | null
  inputPreview: string | null
  expectedPreview: string | null
  sampleSource: string | null
  ruleScore: number | null
  ruleHits: string[] | null
  llmScores: Record<string, unknown> | null
  reasons: Record<string, unknown> | null
  machineFinalScore: number | null
  manualFinalScore: number | null
  authoritativeScore: number | null
  reviewed: boolean
  tokens: number | null
  latencyMs: number | null
}


export interface RunSampleCardPage {
  runId: number | null
  page: number
  size: number
  total: number
  cards: RunSampleCard[]
}


export interface RunDiffRow {
  sampleId: number | null
  inputPreview: string | null
  scoreA: number | null
  scoreB: number | null
  delta: number | null
  verdict: string | null
}


export interface RunDiff {
  runAId: number | null
  runBId: number | null
  targetRefA: string | null
  targetRefB: string | null
  evalsetName: string | null
  evalsetVersion: number | null
  rubricName: string | null
  rows: RunDiffRow[]
  page: number
  size: number
  totalRows: number
  upCount: number
  downCount: number
  flatCount: number
  onlyACount: number
  onlyBCount: number
  comparedCount: number
  avgA: number | null
  avgB: number | null
  avgDiff: number | null
  welchT: number | string
  pValue: number | string
  significant: boolean
  significanceNote: string | null
}


export interface RunCostCard {
  runId: number | null
  tokensTotal: number | null
  scored: number | null
  sampleTotal: number | null
  samplingRate: number | null
  tokensPerSample: number | null
  priceConfigured: boolean
  pricePer1kTokens: number | null
  estimatedCost: number | null
  costPerSample: number | null
  note: string | null
}


export interface ScorecardTrendPoint {
  runId: number | null
  rubricVersion: number | null
  evalsetName: string | null
  evalsetVersion: number | null
  finishedAt: string | null
  scored: number | null
  avgFinalScore: number | null
  passRate: number | null
  tokensTotal: number | null
}


export interface GenerationSessionCard {
  runId: number | null
  targetType: string | null
  targetRef: string | null
  judgeModel: string | null
  finishedAt: string | null
  scored: number | null
  avgFinalScore: number | null
  passCount: number | null
  passRate: number | null
  tokensTotal: number | null
  tokensPerSample: number | null
  latencyAvgMs: number | null
}

export function getRunScorecard(runId: number): Promise<RunScorecard> {
  return request<RunScorecard>({ url: `/judge/runs/${runId}/scorecard`, method: 'get' })
}

export function getRunSampleCards(
  runId: number,
  page = 1,
  size = 50,
): Promise<RunSampleCardPage> {
  return request<RunSampleCardPage>({
    url: `/judge/runs/${runId}/sample-cards`,
    method: 'get',
    params: { page, size },
  })
}

export function diffJudgeRuns(a: number, b: number, page = 1, size = 50,
  sort?: { sortBy?: string; sortDir?: string }): Promise<RunDiff> {
  return request<RunDiff>({
    url: '/judge/runs/diff', method: 'get',
    params: { a, b, page, size, sortBy: sort?.sortBy, sortDir: sort?.sortDir },
  })
}

export function getRunCostCard(runId: number): Promise<RunCostCard> {
  return request<RunCostCard>({ url: `/judge/runs/${runId}/cost-card`, method: 'get' })
}

export function getScorecardTrend(
  rubricName: string,
  rubricDomain: string,
  limit = 50,
): Promise<ScorecardTrendPoint[]> {
  return request<ScorecardTrendPoint[]>({
    url: '/judge/scorecard/trend',
    method: 'get',
    params: { rubricName, rubricDomain, limit },
  })
}

export function getSessionCards(
  rubricName: string,
  rubricDomain: string,
  limit = 50,
): Promise<GenerationSessionCard[]> {
  return request<GenerationSessionCard[]>({
    url: '/judge/scorecard/session-cards',
    method: 'get',
    params: { rubricName, rubricDomain, limit },
  })
}

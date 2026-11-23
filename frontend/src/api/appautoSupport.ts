import { request } from './request'




export interface AppautoEngineSupport {
  
  engineCode: string | null
  engineName: string
  
  enabled: boolean
  
  versionState: string
  
  versionReason: string | null
  
  healthState: string
  
  healthSamples: number
  
  healthPassRate: number
  
  healthGroups: EngineHealthGroup[]
  
  conclusion: string
  
  caliberNote: string
}


export interface EngineHealthGroup {
  
  platform: string | null
  
  samples: number
  
  passCount: number
  
  passRate: number
  
  errorTypeDist: Record<string, number>
  
  aiFailureDist: Record<string, number>
}


export interface AppautoUpgradeAdvice {
  
  risks: string[]
  
  summary: string
  
  breakingChanges: string[]
  
  profile: Record<string, unknown>
  
  conclusion: string
}


export interface AppautoUpgradeWindow {
  slotStart: string
  
  busyBatches: number
  
  recommended: boolean
  
  canaryHint: string | null
}


export interface AppautoUpgradeFlakyLink {
  executorId: number
  targetVersion: string
  upgradedAt: string
  beforeSamples: number
  beforePassRate: number
  afterSamples: number
  afterPassRate: number
  
  dropPoints: number
  
  advice: string
}


export function listEngineSupport(projectId: number): Promise<AppautoEngineSupport[]> {
  return request<AppautoEngineSupport[]>({ url: '/appauto/engine-support', method: 'get', params: { projectId } })
}


export function runUpgradeAdvice(projectId: number, manifestVersion: string): Promise<AppautoUpgradeAdvice> {
  return request<AppautoUpgradeAdvice>({ url: '/appauto/upgrade-advice', method: 'post', data: { projectId, manifestVersion } })
}


export function listUpgradeWindowSlots(): Promise<AppautoUpgradeWindow[]> {
  return request<AppautoUpgradeWindow[]>({ url: '/appauto/upgrade-window', method: 'get' })
}


export function listUpgradeFlakyLinks(): Promise<AppautoUpgradeFlakyLink[]> {
  return request<AppautoUpgradeFlakyLink[]>({ url: '/appauto/upgrade-flaky-links', method: 'get' })
}

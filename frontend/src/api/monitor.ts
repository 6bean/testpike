import { request } from './request'




export interface MonitorTargetItem {
  id: number
  projectId: number
  envId: number
  name: string
  /** HTTP / LOGIN / ONLINE / DB / CERT */
  targetType: string
  probeConfigJson: string | null
  checkIntervalSeconds: number
  enabled: boolean
  
  silenceUntil: string | null
  silenceReason: string | null
  
  lastState: string | null
  lastCheckAt: string | null
  lastCause: string | null
  backoffLevel: number | null
  consecutiveFailures: number | null
}


export interface MonitorCheckLogItem {
  checkedAt: string
  /** ok / fail / deferred */
  outcome: string
  latencyMs: number | null
  detail: string | null
}


export interface MonitorEpisodeItem {
  episodeId: number
  targetId: number
  targetName: string
  targetType: string
  envId: number
  startedAt: string
  recoveredAt: string | null
  ongoing: boolean
  failureCount: number
  causeSummary: string | null
}


export interface MonitorLinkedAssets {
  envId: number
  planCount: number
  planNames: string[]
  suiteCount: number
  suiteNames: string[]
  factoryCount: number
  factoryNames: string[]
  apiRequestCount: number
  apiRequestNames: string[]
}


export interface MonitorTargetSavePayload {
  projectId: number
  envId: number
  name: string
  targetType: string
  /** HTTP={url,expect?} / LOGIN={url,credential=@cred/…} / ONLINE={url,expect?} / DB={connectionId} / CERT={host,port} */
  probeConfig: Record<string, unknown>
  checkIntervalSeconds: number
  enabled: boolean
}


export interface MonitorSilencePayload {
  minutes: number
  reason: string
}


export interface MonitorTargetPage {
  list: MonitorTargetItem[]
  total: number
  page: number
  pageSize: number
}


export interface MonitorWallQuery {
  page: number
  pageSize: number
  /** DISABLED / SILENCED / UP / DOWN / FLAPPING / RECOVERING / UNKNOWN */
  status?: string
  /** HTTP / LOGIN / ONLINE / DB / CERT */
  targetType?: string
  keyword?: string
}


export function pageMonitorTargets(projectId: number, q: MonitorWallQuery): Promise<MonitorTargetPage> {
  return request<MonitorTargetPage>({
    url: '/monitor/targets/page',
    method: 'get',
    params: {
      projectId,
      page: q.page,
      pageSize: q.pageSize,
      status: q.status || undefined,
      targetType: q.targetType || undefined,
      keyword: q.keyword || undefined,
    },
  })
}


export function createMonitorTarget(data: MonitorTargetSavePayload): Promise<number> {
  return request<number>({ url: '/monitor/targets', method: 'post', data })
}


export function findMonitorTarget(id: number): Promise<MonitorTargetItem> {
  return request<MonitorTargetItem>({ url: `/monitor/targets/${id}`, method: 'get' })
}


export function updateMonitorTarget(id: number, data: MonitorTargetSavePayload): Promise<void> {
  return request<void>({ url: `/monitor/targets/${id}`, method: 'put', data })
}


export function setMonitorTargetEnabled(id: number, enabled: boolean): Promise<void> {
  return request<void>({ url: `/monitor/targets/${id}/enabled`, method: 'put', params: { enabled } })
}


export function deleteMonitorTarget(id: number): Promise<void> {
  return request<void>({ url: `/monitor/targets/${id}`, method: 'delete' })
}


export function silenceMonitorTarget(id: number, data: MonitorSilencePayload): Promise<void> {
  return request<void>({ url: `/monitor/targets/${id}/silence`, method: 'post', data })
}


export function clearMonitorSilence(id: number): Promise<void> {
  return request<void>({ url: `/monitor/targets/${id}/silence`, method: 'delete' })
}


export function listMonitorCheckLogs(id: number, limit = 0): Promise<MonitorCheckLogItem[]> {
  return request<MonitorCheckLogItem[]>({ url: `/monitor/targets/${id}/check-logs`, method: 'get', params: { limit } })
}


export function getOpenEpisode(projectId: number, envId: number): Promise<MonitorEpisodeItem | null> {
  return request<MonitorEpisodeItem | null>({
    url: `/monitor/environments/${envId}/open-episode`,
    method: 'get',
    params: { projectId },
  })
}


export function getLinkedAssets(projectId: number, envId: number): Promise<MonitorLinkedAssets> {
  return request<MonitorLinkedAssets>({
    url: `/monitor/environments/${envId}/linked-assets`,
    method: 'get',
    params: { projectId },
  })
}

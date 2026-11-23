import { request, type PageResult } from './request'




export interface ScheduleFireView {
  id: number
  
  kind: string
  
  refId: number
  projectId: number
  
  fireTime: string
  
  status: string
  
  message: string | null
  createdAt: string
}


export function listScheduleFires(params: {
  projectId: number
  kind?: string | null
  status?: string | null
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}): Promise<PageResult<ScheduleFireView>> {
  return request<PageResult<ScheduleFireView>>({ url: '/schedule/fires', method: 'get', params })
}

/**
 * Next-fire preview for a cron expression (B-8 wizard scheduled section).
 * Door = GET /api/v1/schedule/cron-preview; the trigger arithmetic is single
 * sourced server-side (same source the firing scanner uses), so the client
 * only forwards and renders — no local cron parser (red line 6).
 * Blank cron previews an empty list; an invalid expression is refused
 * server-side with human-readable wording surfaced by the caller.
 */
export function previewCron(cron: string, count = 3): Promise<string[]> {
  return request<string[]>({ url: '/schedule/cron-preview', method: 'get', params: { cron, count } })
}




export interface SchedulerHealthRow {
  
  jobName: string
  
  description: string
  
  lockedBy: string | null
  
  lockedAt: string | null
  
  lockUntil: string | null
  
  running: boolean
}

/** Paged health read (keyword = job name / plain-word description; optional sortBy/sortDir = core whitelist key, blank = catalog registration order). */
export function getSchedulerHealth(params: {
  keyword?: string | null
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}): Promise<PageResult<SchedulerHealthRow>> {
  return request<PageResult<SchedulerHealthRow>>({ url: '/scheduler/health', method: 'get', params })
}


export function getSchedulerJobs(): Promise<string[]> {
  return request<string[]>({ url: '/scheduler/jobs', method: 'get' })
}


export function runSchedulerJobNow(job: string): Promise<string> {
  return request<string>({ url: '/scheduler/run-now', method: 'post', params: { job } })
}

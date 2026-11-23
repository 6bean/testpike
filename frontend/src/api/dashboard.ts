import { request } from './request'




export interface ProjectStat {
  id: number
  name: string
  code: string
  caseCount: number
}


export interface ActivityItem {
  id: number
  projectId?: number | null
  eventType: string
  title: string
  dedupKey?: string | null
  createdAt?: string
}


export interface DashboardOverview {
  pendingReviewCount: number
 
  unassignedReviewCount: number
  
  overdueReviewCount: number
  runningTaskCount: number
  unclosedDefectCount: number
  projects: ProjectStat[]
  activities: ActivityItem[]
}


export function getDashboardOverview(): Promise<DashboardOverview> {
  return request<DashboardOverview>({ url: '/dashboard/overview', method: 'get' })
}

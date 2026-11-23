import { request } from './request'




export interface MatrixCaseColumn {
  id: number
  
  caseNo: string
  title: string
  /** 0=P0 1=P1 2=P2 */
  priority: number
  /** web/mobile/pos/multi */
  endType: string
}


export interface MatrixRequirementRow {
  id: number
  title: string
 
  covered: boolean
  
  caseIds: number[]
}


export interface MatrixChapter {
  
  chapter: string | null
  requirements: MatrixRequirementRow[]
}


export interface CoverageMatrixView {
  chapters: MatrixChapter[]
  cases: MatrixCaseColumn[]
  
  totalRequirements: number
  
  coveredRequirements: number
  
  uncoveredRequirementIds: number[]
}


export interface CoverageMatrixQuery {
  chapter?: string
  priority?: number
  endType?: string
}


export function getCoverageMatrix(projectId: number, params?: CoverageMatrixQuery): Promise<CoverageMatrixView> {
  return request<CoverageMatrixView>({ url: `/projects/${projectId}/coverage`, method: 'get', params })
}

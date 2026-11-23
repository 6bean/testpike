import { ht } from '../utils/dictLabel'
import { request } from './request'




export interface ImportItem {
  rowNumber: number
  success: boolean
  reason?: string | null
  entityId?: number | null
}


export interface ImportResult {
  successCount: number
  failCount: number
  items: ImportItem[]
}


type ProgressFn = (percent: number) => void


export function importCases(projectId: number, file: File, targetStatus: number, onProgress?: ProgressFn): Promise<ImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('projectId', String(projectId))
  fd.append('targetStatus', String(targetStatus))
  return request<ImportResult>({
    url: '/import/cases',
    method: 'post',
    data: fd,
    timeout: 120000,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}


export function retryFailedCases(projectId: number, file: File, retryRows: number[], targetStatus: number, onProgress?: ProgressFn): Promise<ImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('projectId', String(projectId))
  fd.append('targetStatus', String(targetStatus))
  retryRows.forEach((row) => fd.append('retryRows', String(row)))
  return request<ImportResult>({
    url: '/import/cases/retry',
    method: 'post',
    data: fd,
    timeout: 120000,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}


export function importRequirements(projectId: number, file: File, onProgress?: ProgressFn): Promise<ImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('projectId', String(projectId))
  return request<ImportResult>({
    url: '/import/requirements',
    method: 'post',
    data: fd,
    timeout: 120000,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
}


export type ImportKind = 'cases' | 'requirements'


export async function downloadImportTemplate(kind: ImportKind): Promise<void> {
  const data = await request<string>({ url: `/import/templates/${kind}`, method: 'get', timeout: 60000 })
  if (!data) {
    throw new Error(ht('http.templateEmpty'))
  }
  const binary = atob(data)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  const blob = new Blob([bytes], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = kind === 'cases' ? ht('http.importTemplateCases') : ht('http.importTemplateRequirements')
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

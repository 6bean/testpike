import { request } from './request'




export interface HelpDocItem {
  id: number
  
  domainCode: string
  title: string
  
  bodyMd: string | null
  sortNo: number
  
  version: string
  updatedAt: string
}


export function listHelpDocs(): Promise<HelpDocItem[]> {
  return request<HelpDocItem[]>({ url: '/help/docs', method: 'get' })
}


export function getHelpDomainDocs(domainCode: string): Promise<HelpDocItem[]> {
  return request<HelpDocItem[]>({ url: `/help/docs/${encodeURIComponent(domainCode)}`, method: 'get' })
}

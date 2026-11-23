import { request } from './request'




export interface PromptTemplateItem {
  id: number
  
  name: string
  
  domain: string
  
  version: number
  
  content: string
  
  status: number
  
  createdBy: number | null
  
  rollbackTo: number | null
  
  createdAt: string
}


export interface PromptTemplateFamilyItem {
  
  domain: string
  
  name: string
  
  latestId: number
  
  latestVersion: number
  
  latestStatus: number
  
  versionCount: number
  
  updatedAt: string
}


export const TEMPLATE_STATUS_KEYS: Record<number, string> = {
  1: 'promptTpl.statusGray',
  2: 'promptTpl.statusFull',
  3: 'promptTpl.statusHistory',
}


export function savePromptTemplate(input: {
  name: string
  domain: string
  content: string
}): Promise<PromptTemplateItem> {
  return request<PromptTemplateItem>({
    url: '/prompt-templates',
    method: 'post',
    data: input,
  })
}


export function rollbackPromptTemplate(sourceId: number): Promise<PromptTemplateItem> {
  return request<PromptTemplateItem>({
    url: `/prompt-templates/${sourceId}/rollback`,
    method: 'post',
  })
}


export function listPromptTemplateVersions(name: string, domain: string): Promise<PromptTemplateItem[]> {
  return request<PromptTemplateItem[]>({
    url: '/prompt-templates/versions',
    method: 'get',
    params: { name, domain },
  })
}


export function listPromptTemplateFamilies(domain?: string): Promise<PromptTemplateFamilyItem[]> {
  return request<PromptTemplateFamilyItem[]>({
    url: '/prompt-templates/families',
    method: 'get',
    params: domain ? { domain } : undefined,
  })
}


export function promotePromptTemplate(id: number): Promise<PromptTemplateItem> {
  return request<PromptTemplateItem>({
    url: `/prompt-templates/${id}/promote`,
    method: 'post',
  })
}

import { td } from '../utils/dictLabel'
import { request, type PageResult } from './request'




export const FACTORY_TYPE_LABEL: Record<number, string> = {
  get 1() { return td('dict.factory.type.api') },
  get 2() { return td('dict.factory.type.sql') },
  get 3() { return td('dict.factory.type.python') },
}


export const FACTORY_STATUS_LABEL: Record<number, string> = {
  get 1() { return td('dict.common.status.enabled') },
  get 2() { return td('dict.common.status.disabled') },
}


export const RUN_STATUS_LABEL: Record<number, string> = {
  get 1() { return td('dict.factory.run.running') },
  get 2() { return td('dict.factory.run.success') },
  get 3() { return td('dict.factory.run.fail') },
}

export interface DataFactoryView {
  id: number
  projectId: number
  envId: number
  envName: string
  type: number
  name: string
  content: string
  paramsJson: string | null
  status: number
  createdByName: string
  createdAt: string
}

export interface DataFactoryRunView {
  id: number
  factoryId: number
  status: number
  resultSummary: string
  createdByName: string
  createdAt: string
}

export interface DataFactoryRunResult {
  factoryId: number
  status: number
  createdCount: number
  poolCount: number
  message: string
}

export interface DataFactoryCreatePayload {
  projectId: number
  envId: number
  type: number
  name: string
  content: string
  paramsJson?: string | null
}

export interface DataFactoryUpdatePayload {
  name: string
  content: string
  status?: number
  paramsJson?: string | null
}

export function listDataFactories(params: {
  projectId: number
  keyword?: string
  type?: number
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}): Promise<PageResult<DataFactoryView>> {
  return request<PageResult<DataFactoryView>>({ url: '/data-factories', method: 'get', params })
}

export function getDataFactory(id: number): Promise<DataFactoryView> {
  return request<DataFactoryView>({ url: `/data-factories/${id}`, method: 'get' })
}

export function createDataFactory(payload: DataFactoryCreatePayload): Promise<DataFactoryView> {
  return request<DataFactoryView>({ url: '/data-factories', method: 'post', data: payload })
}

export function updateDataFactory(id: number, payload: DataFactoryUpdatePayload): Promise<DataFactoryView> {
  return request<DataFactoryView>({ url: `/data-factories/${id}`, method: 'put', data: payload })
}

export function deleteDataFactory(id: number): Promise<void> {
  return request<void>({ url: `/data-factories/${id}`, method: 'delete' })
}


export function runDataFactory(id: number): Promise<DataFactoryRunResult> {
  return request<DataFactoryRunResult>({ url: `/data-factories/${id}/run`, method: 'post' })
}


export function listFactoryRuns(params: {
  id: number
  projectId: number
  page?: number
  size?: number
}): Promise<PageResult<DataFactoryRunView>> {
  return request<PageResult<DataFactoryRunView>>({
    url: `/data-factories/${params.id}/runs`,
    method: 'get',
    params: { projectId: params.projectId, page: params.page, size: params.size },
  })
}



export interface GenFunctionItem {
  
  name: string
  
  description: string
  
  params: string[]
  
  example: string
}


export interface GenFunctionGroup {
  group: string
  functions: GenFunctionItem[]
}


export function getGenkitCatalog(): Promise<GenFunctionGroup[]> {
  return request<GenFunctionGroup[]>({ url: '/data-factories/genkit-catalog', method: 'get' })
}



export const POOL_STATUS_LABEL: Record<number, string> = {
  get 1() { return td('dict.factory.pool.idle') },
  get 2() { return td('dict.factory.pool.occupied') },
  get 3() { return td('dict.factory.pool.invalid') },
}

export interface DataPoolItemView {
  id: number
  projectId: number
  envId: number
  envName: string
  type: string
  value: string
 
  name: string | null
  status: number
  statusLabel: string
  createdAt: string
  createdBy: number | null
}


export function listPoolItems(params: {
  projectId: number
  envId?: number
  type?: string
  status?: number
  page?: number
  size?: number
}): Promise<PageResult<DataPoolItemView>> {
  return request<PageResult<DataPoolItemView>>({ url: '/data-factories/pool-items', method: 'get', params })
}


export function updatePoolItemStatus(id: number, projectId: number, status: number): Promise<void> {
  return request<void>({
    url: `/data-factories/pool-items/${id}/status`,
    method: 'put',
    params: { projectId, status },
  })
}


export function deletePoolItem(id: number, projectId: number): Promise<void> {
  return request<void>({ url: `/data-factories/pool-items/${id}`, method: 'delete', params: { projectId } })
}

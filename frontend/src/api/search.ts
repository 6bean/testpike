import { request } from './request'




export interface SearchItem {
  id: number
  projectId: number
  title: string | null
  subtitle: string | null
}


export interface SearchResults {
  cases: SearchItem[]
  requirements: SearchItem[]
  defects: SearchItem[]
  projects: SearchItem[]
}


export function globalSearch(keyword: string): Promise<SearchResults> {
  return request<SearchResults>({ url: '/search', method: 'get', params: { keyword } })
}

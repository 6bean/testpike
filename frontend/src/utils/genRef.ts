
import type { GenFunctionGroup } from '@/api/dataFactory'


export interface GenParamSpec {
  name: string
  type: string
  optional: boolean
  hint: string
}


export interface GenOption {
  name: string
  
  description: string
  
  example: string
  
  params: string[]
  
  token: string
}


export interface GenOptionGroup {
  label: string
  options: GenOption[]
}


export interface GenCatalogFn {
  name: string
  description: string
  params: string[]
  example: string
}


export function parseParamSchema(line: string): GenParamSpec {
  const head = line.includes('=') ? line.slice(0, line.indexOf('=')) : line
  const hint = line.includes('=') ? line.slice(line.indexOf('=') + 1) : ''
  const namePart = head.includes(':') ? head.slice(0, head.indexOf(':')) : head
  const type = head.includes(':') ? head.slice(head.indexOf(':') + 1) : 'string'
  return {
    name: namePart.replace(/\?$/, '').trim(),
    type: type.trim(),
    optional: namePart.endsWith('?'),
    hint: hint.trim(),
  }
}


export function genSkeleton(fn: GenCatalogFn): string {
  const required = fn.params
    .map(parseParamSchema)
    .filter((p) => !p.optional && p.name)
    .map((p) => `${p.name}=`)
  return '${gen.' + fn.name + '(' + required.join(',') + ')}'
}


export function buildGenGroups(groups: GenFunctionGroup[]): GenOptionGroup[] {
  return groups.map((g) => ({
    label: g.group,
    options: g.functions.map((f) => ({
      name: f.name,
      description: f.description,
      example: f.example,
      params: f.params,
      token: genSkeleton(f),
    })),
  }))
}

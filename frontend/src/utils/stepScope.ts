


export interface ScopeStep {
  action: string
  [field: string]: unknown
}


export interface ScopePathEntry {
  branch: string
  index: number
}


export const REF_NAMESPACES = ['ENV', 'DATA', 'PACK', 'VAR'] as const


export function loopVarOf(node: ScopeStep): string | null {
  if (node.action !== 'loop') return null
  const custom = typeof node.var === 'string' && node.var.trim() ? node.var.trim() : null
  if (custom) return custom
  return Array.isArray(node.items) ? 'item' : 'index'
}


export function collectVisibleVars(steps: ScopeStep[], path: ScopePathEntry[]): string[] {
  const vars: string[] = []
  let list: ScopeStep[] | undefined = steps
  for (const entry of path) {
    if (!list || entry.index < 0 || entry.index >= list.length) break
    const node: ScopeStep = list[entry.index]
    const added = loopVarOf(node)
    if (added && !vars.includes(added)) vars.push(added)
    const branch: unknown = node[entry.branch]
    list = Array.isArray(branch) ? (branch as ScopeStep[]) : undefined
  }
  return vars
}


export function referenceCandidates(visibleVars: string[]): { kind: 'var' | 'namespace'; name: string }[] {
  return [
    ...visibleVars.map((name) => ({ kind: 'var' as const, name })),
    ...REF_NAMESPACES.map((name) => ({ kind: 'namespace' as const, name })),
  ]
}


export function hasStaleVarRef(text: string, visibleVars: string[]): string | null {
  const re = /\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:[:.][^}]*)?\}\}/g
  for (const m of text.matchAll(re)) {
    const name = m[1]
    if ((REF_NAMESPACES as readonly string[]).includes(name)) continue
    if (!visibleVars.includes(name)) return name
  }
  return null
}

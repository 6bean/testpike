import type { LocationQuery, LocationQueryRaw, RouteLocationNormalizedLoaded } from 'vue-router'




export function resolveProjectFromQuery(route: RouteLocationNormalizedLoaded, allowed: number[]): number {
  const qp = Number(route.query.projectId)
  return qp && allowed.includes(qp) ? qp : 0
}


export function withProjectQuery(currentQuery: LocationQuery, projectId: number | null): { query: LocationQueryRaw } {
  const { projectId: _dropped, ...rest } = currentQuery
  return {
    query: projectId == null ? { ...rest } : { ...rest, projectId },
  }
}

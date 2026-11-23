
export interface ProjectRouteResolution {
  
  projectId: number | null
  
  writeBack: boolean
}


function parseQueryId(raw: string | (string | null)[] | undefined): number | null {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (!value) return null
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : null
}

export function resolveProjectRouteParam(
  queryProjectId: string | (string | null)[] | undefined,
  selectedIds: number[] | null | undefined,
): ProjectRouteResolution {
  const fromQuery = parseQueryId(queryProjectId)
  if (fromQuery != null) return { projectId: fromQuery, writeBack: false }
  const primary = selectedIds && selectedIds.length > 0 ? selectedIds[0] : null
  return primary != null ? { projectId: primary, writeBack: true } : { projectId: null, writeBack: false }
}

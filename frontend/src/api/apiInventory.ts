/**
 * API inventory (contract endpoint list) — mirror of the admin bridge
 * /api/v1/api-inventory/{endpoints,coverage} (docs/02 contract, .
 * The request layer already unwraps the Response envelope, so callers see
 * the payload directly (apiSpec.ts precedent). Chinese contract names are
 * URL-encoded at the bridge layer (RestCoreApiSpecClient.history precedent),
 * which is why specName travels as a plain param here.
 */
import { request, type PageResult } from './request'

/** One contract-endpoint row (mirror of admin InventoryEndpointView; the
 *  freshness verdict and every count are core single-source — the page only
 *  renders them and never recomputes (no second judgment ledger)). */
export interface InventoryEndpointItem {
  method: string
  path: string
  endpointKey: string
  title: string | null
  parametersCount: number
  securityRefs: string[]
  deprecated: boolean
  excludedFromCoverage: boolean
  freshness: 'stable' | 'changed' | 'removed'
  requestsCreated: number
  casesPromoted: number
  mockCovered: boolean
  mockAiFallbackOnly: boolean
}

/** Coverage summary card (mirror of admin InventoryCoverageSummary).
 *  specVersion 0 = the name chain has no rows in this project. Denominator
 *  = latest-version rows that are not deprecated (W-c2/R-9 ruling). */
export interface InventoryCoverageSummary {
  projectId: number
  specName: string
  specVersion: number
  total: number
  endpointsWithRequests: number
  uncoveredCount: number
  coveragePercent: number
}

/** Paged endpoint rows of the anchored contract version (page 1-based,
 *  page-size default 20; the server caps oversize via PageQueryPolicy).
 *  uncoveredOnly=true pages over the working list instead (rows in the
 *  coverage denominator nobody has an asset for yet — the server slices
 *  before paging so totals stay honest; deprecated/removed rows never
 *  appear, mirroring the summary card). */
export function pageInventoryEndpoints(
  projectId: number,
  specName: string,
  page = 1,
  pageSize = 20,
  uncoveredOnly = false,
): Promise<PageResult<InventoryEndpointItem>> {
  return request<PageResult<InventoryEndpointItem>>({
    url: '/api-inventory/endpoints',
    method: 'get',
    params: { projectId, specName, page, pageSize, uncoveredOnly },
  })
}

/** Coverage summary for the anchored version of one contract name chain. */
export function getInventoryCoverage(
  projectId: number,
  specName: string,
): Promise<InventoryCoverageSummary> {
  return request<InventoryCoverageSummary>({
    url: '/api-inventory/coverage',
    method: 'get',
    params: { projectId, specName },
  })
}

/**
 * stage-2 layout contract (source-mirror posture of
 * t368PromotionSurface.test.ts — assertions read SFC/TS sources as text).
 *
 * What the five-minute journey owes the interface inventory page:
 * 1. the api layer mirrors the admin bridge uncoveredOnly flag added on the
 * server side (no second filtering ledger in the browser);
 * 2. every badge on an inventory row is click-reachable to its asset door
 * carrying the project segment + the endpoint path as the pre-filter
 * keyword (deep links reuse the existing list params, no new query
 * grammar, no route aliases);
 * 3. the uncovered number turns into a working list in place: server-side
 * uncoveredOnly segment + an always-visible active chip while the flag is
 * on (SE rule 2 — a hidden filter is a lie);
 * 4. the destination pages actually consume the keyword they receive:
 * mock endpoint inventory prefills its server-side keyword box, the API
 * test sandbox filters its request list client-side;
 * 5. jump entries to a permission-gated door are disabled with plain
 * language instead of bouncing off the router guard;
 * 6. every new string lives in both locale files (i18n dual-track).
 */
import { describe, expect, it } from 'vitest'

import apiInventoryRaw from '@/api/apiInventory.ts?raw'
import inventoryViewRaw from '@/views/apitest/ApiInventoryView.vue?raw'
import apiTestViewRaw from '@/views/apitest/ApiTestView.vue?raw'
import mockEndpointsRaw from '@/views/mock/MockEndpointsView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T370 stage-2 inventory deep links', () => {
  it('api layer mirrors the uncoveredOnly bridge flag', () => {
    expect(apiInventoryRaw).toContain('uncoveredOnly')
    // the flag travels as a request param — the browser never re-slices rows
    expect(apiInventoryRaw).toMatch(/params:\s*\{[^}]*uncoveredOnly/)
  })

  it('inventory page passes the flag through the same api call it already uses', () => {
    expect(inventoryViewRaw).toContain('uncoveredOnly')
    expect(inventoryViewRaw).toMatch(/pageInventoryEndpoints\([^)]*uncoveredOnly/)
  })

  it('row badges jump to their asset doors with project + path keyword', () => {
    // cases door reuses the CaseListView keyword consumer
    expect(inventoryViewRaw).toMatch(/path:\s*'\/cases'/)
    // request sandbox door
    expect(inventoryViewRaw).toMatch(/path:\s*'\/api-test'/)
    // mock serving inventory door
    expect(inventoryViewRaw).toMatch(/path:\s*'\/mock\/endpoints'/)
    // keyword carries the endpoint path (single param grammar, no alias routes)
    expect(inventoryViewRaw).toMatch(/keyword:/)
    expect(inventoryViewRaw).not.toMatch(/path:\s*'\/cases\/'/)
  })

  it('uncovered working list is an in-place filter with a visible chip', () => {
    expect(inventoryViewRaw).toContain('api-inventory-uncovered-toggle')
    expect(inventoryViewRaw).toContain('api-inventory-uncovered-chip')
    // chip renders only while the filter is active, closable clears it
    expect(inventoryViewRaw).toMatch(/v-if="uncoveredOnly"[\s\S]*api-inventory-uncovered-chip/)
  })

  it('mock jump honors the perm gate with honest guidance', () => {
    expect(inventoryViewRaw).toContain('perm:mock:read')
    expect(inventoryViewRaw).toContain('jumpNoMockPerm')
  })

  it('mock endpoint page consumes the deep-link keyword', () => {
    expect(mockEndpointsRaw).toContain('useRoute')
    expect(mockEndpointsRaw).toMatch(/route\.query\.keyword/)
  })

  it('api-test page consumes the deep-link keyword and filters requests', () => {
    expect(apiTestViewRaw).toMatch(/route\.query\.keyword/)
    expect(apiTestViewRaw).toContain('apitest-request-filter')
    expect(apiTestViewRaw).toMatch(/requestKeyword/)
    expect(apiTestViewRaw).toContain('filteredRequests')
  })

  it('new copy exists in both locales (dual-track, zero hard-coded Chinese)', () => {
    for (const key of ['uncoveredChip', 'viewUncovered', 'jumpNoMockPerm']) {
      expect(zhRaw).toContain(key)
      expect(enRaw).toContain(key)
    }
    expect(zhRaw).toContain('requestFilterPh')
    expect(enRaw).toContain('requestFilterPh')
  })

  it('no invented bookkeeping codes leak into user-visible copy keys', () => {
    // layout contract guards against future edits reintroducing T-numbers
    // into locale *values* consumed by this page (screen-display ruling)
    const inventorySection = zhRaw.match(/apiInventory:\s*\{[\s\S]*?\n {2}\}/)
    expect(inventorySection).not.toBeNull()
    expect(inventorySection![0]).not.toMatch(/T\d{3}|§\d|V\d{2,}/)
  })
})

package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockServingEndpointPageView;

/**
 * Project-wide serving inventory read side — the cross-space "which mock
 * endpoints can I actually call right now" question the per-space doc page
 * structurally cannot answer (an endpoint's identity carries its space's
 * routing handle, so only a project-scoped flat window is honest).
 * <p>Read-only by design: every mutation still goes through
 * {@link MockStubService} / {@link MockSpaceService} save gates. This service
 * never writes, never re-validates, never recomputes the URL (the consumer
 * composes it from the raw parts — one function owns that shape).
 */
public interface MockServingInventoryService {

    /**
     * One paged window of every endpoint (stub row) belonging to a project,
     * routing order first then within-space evaluation order, with the last
     * 7 days' matched-hit count carried per row (honesty field: a published
     * rule with zero hits means nobody is calling it).
     *
     * @param projectId owning project (H1 isolation — other projects leak nothing)
     * @param page      1-based page index (clamped by the shared PageWindow)
     * @param pageSize  window size (clamped, never a whole-table dump)
     * @param status    optional lifecycle filter (0 draft / 1 published /
     *                  2 retired); null = every status
     * @param method    optional verb filter (exact, uppercase; null = every verb)
     * @param keyword   optional fuzzy filter over path / rule name / space name
     *                  (server-side so a huge inventory never screens a lie
     *                  about "what is on this page"); null/blank = no filter
     */
    MockServingEndpointPageView listProjectEndpoints(long projectId, int page, int pageSize,
                                                     Integer status, String method, String keyword);

    /**
 * Sorting form of the same window : the
 * ORDER BY moves into SQL through a physical-column whitelist, so the
 * page counter and the truthful total keep meaning across pages even for
 * inventories bigger than one screen.
 *
 * @param sortBy whitelist key (space_name/space_status/path_pattern/method/
 * hits_7d/updated_at); null/blank = historical routing order;
 * anything else is refused fail-closed with a plain word
 * @param sortDir asc when exactly "asc" (case-insensitive), otherwise desc
 */
    MockServingEndpointPageView listProjectEndpoints(long projectId, int page, int pageSize,
                                                     Integer status, String method, String keyword,
                                                     String sortBy, String sortDir);
}

package com.sixbean.testpike.core.spec.api.dto;

/**
 * Inventory query input: one project × one contract name chain, paged.
 * <p>The anchor is the max(spec_version) row of the name chain inside the
 * project (H1 isolation is enforced by the caller's guard and by the
 * project_id predicate here — never a cross-project scan).
 *
 * @param projectId required project scope
 * @param specName contract name (version chain key)
 * @param page 1-based page number
 * @param pageSize page size (capped by PageQueryPolicy, oversize is rejected)
 * @param uncoveredOnly when true the paged window is cut over the uncovered
 * segment only (in-scope rows with zero requests) — the
 * badge "go work the list" filter. The segment is applied
 * server-side before paging so page totals stay honest at
 * worst scale (a client-side window filter would screen a
 * lie); rows off the coverage denominator (deprecated /
 * removed) never appear as "uncovered" — the same card
 * on the page would then contradict the list.
 */
public record InventoryQuery(long projectId, String specName, int page, int pageSize,
                             boolean uncoveredOnly) {
}

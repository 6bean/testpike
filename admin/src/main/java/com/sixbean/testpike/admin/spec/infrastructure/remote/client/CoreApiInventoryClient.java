package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.admin.spec.api.dto.InventoryEndpointView;

/**
 * core contract-inventory query client (admin bridge over /internal/api-inventory).
 * <p>Paging semantics, anchor-version selection and asset counts are core
 * single-source; the bridge forwards parameters and mirrors rows verbatim.
 */
public interface CoreApiInventoryClient {

    /** Paged endpoint rows of the anchored contract version with asset counts.
 * uncoveredOnly pages over the core-side working-list segment (badge filter);
 * the bridge forwards the flag, never re-filters in memory. */
    PageView<InventoryEndpointView> endpoints(long projectId, String specName,
                                              int page, int pageSize,
                                              boolean uncoveredOnly);

    /** Coverage summary card (denominator = latest non-deprecated rows). */
    InventoryCoverageSummary coverage(long projectId, String specName);
}

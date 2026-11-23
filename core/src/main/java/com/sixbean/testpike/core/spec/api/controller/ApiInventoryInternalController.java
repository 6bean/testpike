package com.sixbean.testpike.core.spec.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.core.spec.api.dto.InventoryEndpointView;
import com.sixbean.testpike.core.spec.api.dto.InventoryQuery;
import com.sixbean.testpike.core.spec.domain.service.ApiInventoryQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract inventory query internal endpoints (core service layer, consumed by
 * admin over HTTP; 02 contract family).
 * <p>Permissions (H1 project isolation) are enforced admin-side; the project_id
 * parameter is a required primitive here so a missing scope fails as 400 at the
 * bridge instead of unboxing null inside admin. Paging is mandatory — endpoint
 * rows reach the hundreds (worst-scale display rule).
 */
@RestController
@RequestMapping("/internal/api-inventory")
public class ApiInventoryInternalController {

    private final ApiInventoryQueryService inventoryService;

    public ApiInventoryInternalController(ApiInventoryQueryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** Paged endpoint rows of the anchored contract version with asset counts.
 * uncoveredOnly=true pages over the working list (in-scope rows nobody has
 * built a request on yet) — the segment happens server-side, see core DTO. */
    @GetMapping("/endpoints")
    public Response<PageResult<InventoryEndpointView>> endpoints(
            @RequestParam long projectId,
            @RequestParam String specName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "false") boolean uncoveredOnly) {
        return Response.builderSuccess(inventoryService.queryEndpoints(
                new InventoryQuery(projectId, specName, page, pageSize, uncoveredOnly)));
    }

    /** Coverage summary card (denominator = latest-version non-deprecated rows). */
    @GetMapping("/coverage")
    public Response<InventoryCoverageSummary> coverage(@RequestParam long projectId,
                                                       @RequestParam String specName) {
        return Response.builderSuccess(inventoryService.queryCoverage(projectId, specName));
    }
}

package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockServingEndpointPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubUpdateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockStubView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-stub mutation doors + the project-wide serving inventory (the two
 * closures the space-nested controllers could not carry: a stub id is global,
 * and "all endpoints of the project" spans every space at once).
 * <p>Permission posture mirrors the space/stub list family exactly:
 * read doors = perm:mock:read, edit/delete doors = perm:mock:write. All
 * save-chain gates, the optimistic lock and the inventory ordering live in
 * core — this bridge authenticates and forwards, never re-decides (red line 6).
 */
@RestController
@RequestMapping("/api/v1/mock")
public class MockStubItemController {

    private final CoreMockRuleClient coreMockRuleClient;

    public MockStubItemController(CoreMockRuleClient coreMockRuleClient) {
        this.coreMockRuleClient = coreMockRuleClient;
    }

    /**
     * Edit one rule in place. The body carries the row's expectedVersion (the
     * value the editor read); a stale echo returns the plain-word version
     * conflict from core untouched. Editing a rule of a PUBLISHED space takes
     * effect on the traffic plane within one rule-load poll — the docs page
     * warns before submitting, the gate itself is core's.
     */
    @PostMapping("/stubs/{stubId}/update")
    @RequirePermission("perm:mock:write")
    public Response<MockStubView> update(@PathVariable long stubId,
                                         @RequestBody MockStubUpdateRequest request) {
        return Response.builderSuccess(coreMockRuleClient.updateStub(stubId, request.toInput()));
    }

    /** Remove one rule (traffic stops serving it on the next poll). */
    @DeleteMapping("/stubs/{stubId}")
    @RequirePermission("perm:mock:write")
    public Response<Void> delete(@PathVariable long stubId) {
        coreMockRuleClient.deleteStub(stubId);
        return Response.builderSuccess(null);
    }

    /**
     * 7-day impact count of one rule (matched serves). The confirmation door
     * asks this BEFORE showing the delete prompt, so the operator sees "this
     * endpoint was called N times last week" instead of guessing.
     */
    @GetMapping("/stubs/{stubId}/recent-hits")
    @RequirePermission("perm:mock:read")
    public Response<Long> recentHits(@PathVariable long stubId,
                                     @RequestParam(defaultValue = "7") int days) {
        return Response.builderSuccess(coreMockRuleClient.recentStubHits(stubId, days));
    }

    /**
     * Project-wide serving inventory page — every mock endpoint across the
     * project's spaces in routing order with per-rule 7-day hit counts.
     * Filters (all optional, evaluated server-side so huge inventories screen
     * truthfully): status 0 draft / 1 published / 2 retired, exact verb,
     * fuzzy keyword over path / rule name / space name.
     */
    @GetMapping("/serving-inventory/page")
    @RequirePermission("perm:mock:read")
    public Response<MockServingEndpointPageView> servingInventoryPage(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(coreMockRuleClient.pageServingEndpoints(
                projectId, page, pageSize, status, method, keyword, sortBy, sortDir));
    }
}

package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockHitPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubImportResultView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hit-ledger admin bridge the request inspector's
 * data door).
 * <p>Permission key: traffic (perm:mock:traffic, RUN family). Browsing mocked
 * traffic is an observing-act of the executing roles per the AG ruling — the
 * read key deliberately does NOT open this door.
 * <p>Thin bridge: paging clamp, matched filter spectrum, and the ghost-space
 * refusal are all core MockHitQueryService semantics — nothing re-checked and
 * nothing re-masked here (red line 6); human-worded errors propagate untouched.
 */
@RestController
@RequestMapping("/api/v1/mock/spaces/{spaceId}/hits")
public class MockHitController {

    private final CoreMockRuleClient coreMockRuleClient;

    public MockHitController(CoreMockRuleClient coreMockRuleClient) {
        this.coreMockRuleClient = coreMockRuleClient;
    }

    /**
     * One inspector page for a space, newest serve-time first.
     * matched absent = both spectra, true = hits only, false = misses only.
     */
    @GetMapping
    @RequirePermission("perm:mock:traffic")
    public Response<MockHitPageView> list(@PathVariable long spaceId,
                                          @RequestParam(required = false) Boolean matched,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int pageSize,
                                          @RequestParam(required = false) String sortBy,
                                          @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                coreMockRuleClient.listHits(spaceId, matched, page, pageSize, sortBy, sortDir));
    }

    /**
 * "Make this request a stub" : freeze one answered ledger
 * row as an exact-path rule through core's single import channel.
 * Write-plane permission — a rule lands, unlike the traffic read above;
 * refusal wording and the idempotent re-click skip come back from core
 * untouched (the bridge re-checks nothing, red line 6).
 */
    @PostMapping("/{hitId}/promote")
    @RequirePermission("perm:mock:write")
    public Response<MockStubImportResultView> promote(@PathVariable long spaceId,
                                                      @PathVariable long hitId) {
        return Response.builderSuccess(coreMockRuleClient.promoteHit(spaceId, hitId));
    }
}

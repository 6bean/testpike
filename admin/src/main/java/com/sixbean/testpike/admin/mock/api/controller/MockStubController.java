package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockStubCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockStubPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mock stub rule admin bridge ( pack C — rules nested under their space,
 * matching the /mocks/{spaceKey} routing shape the traffic plane serves).
 * <p>Permission keys: list=read (perm:mock:read), create=write (perm:mock:write).
 * <p>Thin bridge: method shape, path-pattern type, regex compilability and the
 * matcher/response skeletons are all checked by core MockRuleValidator at the
 * save-chain single point — the json payloads pass through verbatim (red line 6).
 */
@RestController
@RequestMapping("/api/v1/mock/spaces/{spaceId}/stubs")
public class MockStubController {

    private final CoreMockRuleClient coreMockRuleClient;

    public MockStubController(CoreMockRuleClient coreMockRuleClient) {
        this.coreMockRuleClient = coreMockRuleClient;
    }

    /** Rules of one space in evaluation order (first match wins — never re-sorted here). */
    @GetMapping
    @RequirePermission("perm:mock:read")
    public Response<List<MockStubView>> list(@PathVariable long spaceId) {
        return Response.builderSuccess(coreMockRuleClient.listStubs(spaceId));
    }

    /**
 * One paged window of the space's rules in evaluation order
 * — display-scale iron law; the docs page's bounded windows consume this
 * door). Evaluation order, clamp and the truthful total are core semantics
 * forwarded verbatim; the read key opens both doors (zero new keys).
 */
    @GetMapping("/page")
    @RequirePermission("perm:mock:read")
    public Response<MockStubPageView> page(@PathVariable long spaceId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(coreMockRuleClient.pageStubs(spaceId, page, pageSize));
    }

    /** Create a rule under the space (spaceId from the path, payload from the body). */
    @PostMapping
    @RequirePermission("perm:mock:write")
    public Response<Long> create(@PathVariable long spaceId,
                                 @RequestBody MockStubCreateRequest request) {
        return Response.builderSuccess(coreMockRuleClient.createStub(spaceId, request.toInput()));
    }
}

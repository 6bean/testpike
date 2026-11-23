package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockScenarioCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioView;
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
 * Mock scenario admin bridge ( — stateful machines nested under their
 * space, same routing shape as the stub lanes).
 * <p>Permission keys: list=read (perm:mock:read), create=write
 * (perm:mock:write) — scenario declarations are rule-plane configuration, so
 * the audience split mirrors the stub bridge exactly.
 * <p>Thin bridge: name shape, machine JSON validity, duplicate-name refusal
 * and the declared-state gates are the core save-chain single point (red
 * line 6); the definition payload passes through verbatim and human-worded
 * refusals propagate untouched.
 */
@RestController
@RequestMapping("/api/v1/mock/spaces/{spaceId}/scenarios")
public class MockScenarioController {

    private final CoreMockRuleClient coreMockRuleClient;

    public MockScenarioController(CoreMockRuleClient coreMockRuleClient) {
        this.coreMockRuleClient = coreMockRuleClient;
    }

    /** Scenario declarations of one space in name order (empty list = honest empty state). */
    @GetMapping
    @RequirePermission("perm:mock:read")
    public Response<List<MockScenarioView>> list(@PathVariable long spaceId) {
        return Response.builderSuccess(coreMockRuleClient.listScenarios(spaceId));
    }

    /**
 * One paged window of the space's scenarios display-scale
 * iron law: the /mock/scenarios list page consumes this door). Name order
 * (or a whitelisted sort), clamp and the truthful total are core semantics;
 * the read key opens both the full list and the window (zero new permission
 * keys).
 */
    @GetMapping("/page")
    @RequirePermission("perm:mock:read")
    public Response<MockScenarioPageView> page(@PathVariable long spaceId,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int pageSize,
                                               @RequestParam(required = false) String sortBy,
                                               @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                coreMockRuleClient.pageScenarios(spaceId, page, pageSize, sortBy, sortDir));
    }

    /** File a machine declaration under the space (spaceId from the path, payload from the body). */
    @PostMapping
    @RequirePermission("perm:mock:write")
    public Response<Long> create(@PathVariable long spaceId,
                                 @RequestBody MockScenarioCreateRequest request) {
        return Response.builderSuccess(
                coreMockRuleClient.createScenario(spaceId, request.toInput()));
    }
}

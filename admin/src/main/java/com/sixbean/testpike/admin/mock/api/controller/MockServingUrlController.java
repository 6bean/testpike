package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.auth.infrastructure.remote.client.ConfigValueClient;
import com.sixbean.testpike.admin.mock.api.dto.MockServingUrlView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock serving-URL admin bridge the workbench URL card and
 * the copy-curl example read the deployed addresses from here, never from a
 * hard-coded frontend constant).
 * <p>Both values come from core's config single point (K8 hot-update): an
 * operator behind a reverse proxy changes mock.public-base-url once and every
 * card follows. Reusing the shared ConfigValueClient (getValue with fallback)
 * instead of wrapping a second client = red line 6, no duplicated read path.
 * <p>Lane carries perm:mock:read — composing a display URL is a viewing act,
 * the same door family as the space list.
 */
@RestController
@RequestMapping("/api/v1/mock")
public class MockServingUrlController {

    /** Seed default in V231 — the fallback mirrors it so an unreachable core still shows the shipped shape. */
    private static final String DEFAULT_PUBLIC_BASE_URL = "http://localhost:18086";
    /** Seed default in V228 — same posture for the routing root prefix. */
    private static final String DEFAULT_TRAFFIC_BASE_PATH = "/mocks";

    private final ConfigValueClient configValueClient;

    public MockServingUrlController(ConfigValueClient configValueClient) {
        this.configValueClient = configValueClient;
    }

    /** The two configured address pieces for the URL card (values pass through verbatim). */
    @GetMapping("/serving-url")
    @RequirePermission("perm:mock:read")
    public Response<MockServingUrlView> servingUrl() {
        String publicBaseUrl = configValueClient.getValue("mock.public-base-url", DEFAULT_PUBLIC_BASE_URL);
        String trafficBasePath = configValueClient.getValue("mock.traffic.base-path", DEFAULT_TRAFFIC_BASE_PATH);
        return Response.builderSuccess(new MockServingUrlView(
                publicBaseUrl == null ? "" : publicBaseUrl.trim(),
                trafficBasePath == null ? "" : trafficBasePath.trim()));
    }
}

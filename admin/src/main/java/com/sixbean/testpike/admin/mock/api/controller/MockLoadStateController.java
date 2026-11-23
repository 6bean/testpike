package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockLoadStateView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.MockServerLoadClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Traffic-plane self-check admin bridge the request
 * inspector's health-bar door).
 * <p>Permission key: traffic (perm:mock:traffic, RUN family) — same family as
 * the hits door: watching what the plane serves and watching how healthy the
 * plane is are one observing act for one audience (tester and up).
 * <p>Thin by design: the ten fields are mock-server's own honesty projection
 * (AG ruling D-b) — no re-judging, no smoothing, no second staleness rule
 * here (red line 6). An unreachable plane arrives as a human-worded 503 from
 * the client and propagates untouched.
 * <p>The spaceId path segment keeps the URL aligned with the hits family the
 * inspector page already polls (one door shape on the page); instance routing
 * behind it is deferred to the multi-plane ops task, see MockServerLoadClient.
 */
@RestController
@RequestMapping("/api/v1/mock/spaces/{spaceId}/load-state")
public class MockLoadStateController {

    private final MockServerLoadClient mockServerLoadClient;

    public MockLoadStateController(MockServerLoadClient mockServerLoadClient) {
        this.mockServerLoadClient = mockServerLoadClient;
    }

    /** Live self-check of the serving plane, newest read (no cache on purpose). */
    @GetMapping
    @RequirePermission("perm:mock:traffic")
    public Response<MockLoadStateView> loadState(@PathVariable long spaceId) {
        return Response.builderSuccess(mockServerLoadClient.loadState());
    }
}

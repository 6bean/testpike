package com.sixbean.testpike.admin.mock.infrastructure.remote.client;

import com.sixbean.testpike.admin.mock.api.dto.MockLoadStateView;

/**
 * Read door to the mock-server traffic plane's self-check endpoint (
 * . One method by design: the plane exposes exactly one honest
 * face — GET /load-state on its management connector (18085).
 * <p>No spaceId parameter: the current deployment pins one space per
 * mock-server instance and the plane reports which key it holds
 * ({@code pinnedSpaceKey}); resolving spaceId→instance is a multi-plane
 * concern deferred to the ops routing task, inventing it here would be a
 * fake abstraction.
 */
public interface MockServerLoadClient {

    /**
     * Current self-check of the serving plane, values verbatim.
     *
     * @throws com.sixbean.testpike.common.exception.ServiceException
     *         with SERVICE_UNAVAILABLE (50301) and a human message when the
     *         plane does not answer — an unreachable plane must be named,
     *         not rendered as a healthy empty state
     */
    MockLoadStateView loadState();
}

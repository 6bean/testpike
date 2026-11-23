package com.sixbean.testpike.api.mock.infrastructure.remote.client;

import com.sixbean.testpike.api.mock.api.dto.MockCiExportView;
import com.sixbean.testpike.api.mock.api.dto.MockCiSpaceInput;

/**
 * core mock-CI bridge (api→HTTP→core /internal/mock family, service-layering
 * law: outside systems only ever enter through api, and api only talks to core
 * over this client).
 * <p>Zero business logic here — every gate (key shape, env ban, TTL range,
 * ghost-space refusal, count spectrum) is the single point inside core, and
 * core's plain-word failures surface as ServiceException through
 * RestClientInvoker with their original error codes.
 */
public interface CoreMockCiClient {

    /**
     * Create a space (POST /internal/mock/space/create?projectId=&ttlHours=).
     *
     * @param operatorId principal user, rides X-Operator-Id so core audits the
     *                   real pipeline owner instead of a system account
     */
    long createSpace(long projectId, Integer ttlHours, MockCiSpaceInput input, long operatorId);

    /** Dialect export of one space (GET /internal/mock/space/export?spaceId=&format=). */
    MockCiExportView exportSpace(long spaceId, String format);

    /**
     * Hit-ledger count (GET /internal/mock/hit/count?spaceId=&since=&matched=).
     *
     * @param since   ISO-8601 local date-time string, passed raw — parsing and
     *                the ghost-space refusal live in core
     * @param matched null = both spectra, TRUE = matched hits, FALSE = misses
     */
    long countHits(long spaceId, String since, Boolean matched);
}

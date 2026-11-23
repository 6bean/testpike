package com.sixbean.testpike.aiservice.goldenreplay.domain.service;

import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenHeadReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenReplayResult;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenRouteReplayRequest;

/**
 * Golden-set replay: run a domain's fixed sample pack through the two sides
 * of one comparison axis and land the drift verdict on the core ledger
 * (pre-release regression twin of the behavior-outcome scorecard).
 */
public interface GoldenReplayService {

    /**
     * Replays the domain's golden pack through both template versions and
     * records the resulting comparison on the core drift ledger.
     *
     * @throws com.sixbean.testpike.common.exception.ValidationException when the
     *         domain has no registered golden pack, the pack is empty, a
     *         template row is missing, or candidate equals control — every
     *         refusal happens before any model call (no burned tokens on a
     *         malformed comparison).
     */
    GoldenReplayResult replay(GoldenReplayRequest request);

    /**
 * Route axis : replays the family's released template through
 * two model routes so a planned model switch is measured against the
 * golden pack before it ships. Refuses identical routes before any call.
 */
    GoldenReplayResult replayRouteSwitch(GoldenRouteReplayRequest request);

    /**
 * Head axis : replays the family's released template on one
 * pinned model through the old (bare body) and the new (kernel-prefixed)
 * assembler head so an assembly-segment change is measured the same way.
 */
    GoldenReplayResult replayHeadSwitch(GoldenHeadReplayRequest request);
}

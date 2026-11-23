package com.sixbean.testpike.aiservice.goldenreplay.api.controller;

import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenHeadReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenReplayResult;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenRouteReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.domain.service.GoldenReplayService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal replay face (ai-service serves only internal callers): runs the
 * domain's golden pack through two template versions and lands the pair
 * digests on the core drift ledger. It records an evaluation result — data,
 * not a template status change — so the human-only promotion path and the
 * write-confirmation gate stay untouched (red line conserved).
 */
@RestController
@RequestMapping("/internal/golden-replay")
public class GoldenReplayInternalController {

    private final GoldenReplayService replayService;

    public GoldenReplayInternalController(GoldenReplayService replayService) {
        this.replayService = replayService;
    }

    /** Replays both template versions over the registered golden pack. */
    @PostMapping("/run")
    public Response<GoldenReplayResult> run(@RequestBody GoldenReplayRequest request) {
        return Response.builderSuccess(replayService.replay(request));
    }

    /** Route axis : one released template replayed through two model routes. */
    @PostMapping("/run-route")
    public Response<GoldenReplayResult> runRoute(@RequestBody GoldenRouteReplayRequest request) {
        return Response.builderSuccess(replayService.replayRouteSwitch(request));
    }

    /** Head axis : one template+model replayed through old vs new assembler head. */
    @PostMapping("/run-head")
    public Response<GoldenReplayResult> runHead(@RequestBody GoldenHeadReplayRequest request) {
        return Response.builderSuccess(replayService.replayHeadSwitch(request));
    }
}

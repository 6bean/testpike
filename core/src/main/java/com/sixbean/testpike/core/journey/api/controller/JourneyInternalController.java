package com.sixbean.testpike.core.journey.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.journey.api.dto.JourneyCreateInput;
import com.sixbean.testpike.core.journey.api.dto.JourneyInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.core.journey.api.dto.JourneyRunInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInput;
import com.sixbean.testpike.core.journey.domain.service.JourneyRunService;
import com.sixbean.testpike.core.journey.domain.service.JourneyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journey internal endpoints — core service layer, consumed by admin over HTTP
 * (same bridge topology as {@code /internal/programs}).
 *
 * <p>Thin by design: existence checks, duplicate-name rejection, segment pin
 * registration through the shared reference source, per-segment expansion
 * transactions, sign-off ownership and the audit row all live in
 * JourneyService / JourneyRunService — the api layer adds no second judgement
 * (red line). The operator is resolved from the X-Operator-Id header the caller
 * forwards, exactly like the program bridge, so the sign-off guard always sees
 * the real end user rather than the admin service.
 */
@RestController
@RequestMapping("/internal/journeys")
public class JourneyInternalController {

    private final JourneyService journeyService;
    private final JourneyRunService runService;

    public JourneyInternalController(JourneyService journeyService,
                                     JourneyRunService runService) {
        this.journeyService = journeyService;
        this.runService = runService;
    }

    // ── plan surface ────────────────────────────────────────────────────────

    /** Create an active journey inside a program; duplicate names are rejected by the service. */
    @PostMapping
    public Response<Long> create(@RequestBody JourneyCreateInput input) {
        JourneyInfo created = journeyService.createJourney(
                OperatorContext.currentOperatorId(), input);
        return Response.builderSuccess(created.id());
    }

    /** Append one ordered segment; asset targets are pinned via the shared reference source. */
    @PostMapping("/{id}/segments")
    public Response<JourneySegmentInfo> addSegment(@PathVariable long id,
                                                   @RequestBody JourneySegmentInput input) {
        return Response.builderSuccess(journeyService.addSegment(id, input));
    }

    /** Journey aggregate: header plus all segments ordered by seqNo. */
    @GetMapping("/{id}")
    public Response<JourneyInfo> getJourney(@PathVariable long id) {
        return Response.builderSuccess(journeyService.getJourney(id));
    }

    /** Paged journey list under a program (S-group worst-scale contract; keyword = name contains). */
    @GetMapping("/page")
    public Response<JourneyPageView> page(@RequestParam long programId,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int pageSize,
                                          @RequestParam(required = false) String keyword) {
        return Response.builderSuccess(journeyService.pageJourneys(programId, page, pageSize, keyword));
    }

    // ── run surface ─────────────────────────────────────────────────────────

    /** Expand the plan into per-project batches (honest partial launch, segment-level failures named). */
    @PostMapping("/{id}/runs")
    public Response<JourneyRunInfo> startRun(@PathVariable long id) {
        return Response.builderSuccess(
                runService.startRun(OperatorContext.currentOperatorId(), id));
    }

    /** Run with all segment rows ordered by seqNo — the timeline traffic-light source. */
    @GetMapping("/runs/{runId}")
    public Response<JourneyRunInfo> getRun(@PathVariable long runId) {
        return Response.builderSuccess(runService.getRun(runId));
    }

    /** Exit sign-off, allowed exactly once and only for the journey owner (enforced in the service). */
    @PostMapping("/runs/{runId}/signoff")
    public Response<JourneyRunInfo> signOff(@PathVariable long runId) {
        return Response.builderSuccess(
                runService.signOff(OperatorContext.currentOperatorId(), runId));
    }
}

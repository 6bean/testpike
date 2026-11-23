package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockPlaybackResultView;
import com.sixbean.testpike.admin.mock.api.dto.MockRecordingPageView;
import com.sixbean.testpike.admin.mock.api.dto.MockRecordingStartRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockRecordingView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recording control-plane admin bridge cassette boxes
 * nested under their space, same routing shape as scenario/stub lanes).
 * <p>Permission keys mirror the rule-plane split: list=read (perm:mock:read),
 * open/seal=write (perm:mock:write) — opening a proxy recording is a
 * configuration act against a live upstream, so it needs the write audience.
 * <p>Thin bridge on purpose: the published-state gate, target-shape refusal,
 * allowlist fail-closed and host-by-name refusal are core's save-chain single
 * point (red line 6); only the "body carries no target" case is caught here
 * so the user gets a stable 40001 wording without a pointless round trip —
 * core still re-checks every rule for direct internal callers.
 */
@RestController
@RequestMapping("/api/v1/mock/spaces/{spaceId}/recordings")
public class MockRecordingController {

    private final CoreMockRuleClient coreMockRuleClient;

    public MockRecordingController(CoreMockRuleClient coreMockRuleClient) {
        this.coreMockRuleClient = coreMockRuleClient;
    }

    /** Cassette boxes of one space, newest first. */
    @GetMapping
    @RequirePermission("perm:mock:read")
    public Response<List<MockRecordingView>> list(@PathVariable long spaceId) {
        return Response.builderSuccess(coreMockRuleClient.listRecordings(spaceId));
    }

    /**
 * One paged window of the space's boxes display-scale
 * iron law: the recording ledger page consumes this door). Clamp, the
 * whitelisted sort order and the truthful total are core semantics; the
 * read key opens both the full list and the window (zero new permission
 * keys).
 */
    @GetMapping("/page")
    @RequirePermission("perm:mock:read")
    public Response<MockRecordingPageView> page(@PathVariable long spaceId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                @RequestParam(required = false) String sortBy,
                                                @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                coreMockRuleClient.pageRecordings(spaceId, page, pageSize, sortBy, sortDir));
    }

    /** Open a recording box (target from the body, gated by core's allowlist ladder). */
    @PostMapping
    @RequirePermission("perm:mock:write")
    public Response<Long> start(@PathVariable long spaceId,
                                @RequestBody MockRecordingStartRequest request) {
        if (request == null || request.targetBaseUrl() == null
                || request.targetBaseUrl().isBlank()) {
            throw new ServiceException("请先填写要录制/代理的上游基址（如 http://svc.test/api）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(
                coreMockRuleClient.startRecording(spaceId, request.targetBaseUrl().trim()));
    }

    /** Seal an open box (recording→stopped; seal stamps and refusals are core's wording). */
    @PostMapping("/{recordingId}/stop")
    @RequirePermission("perm:mock:write")
    public Response<MockRecordingView> stop(@PathVariable long spaceId,
                                            @PathVariable long recordingId) {
        return Response.builderSuccess(coreMockRuleClient.stopRecording(recordingId));
    }

    /**
 * Replay preview of one box : the recorded frames mapped
 * into save-ready drafts under strict|fuzzy. Read-plane permission — a
 * preview persists nothing, the confirmation step below does the filing.
 */
    @GetMapping("/{recordingId}/playback/preview")
    @RequirePermission("perm:mock:read")
    public Response<List<MockStubDraftView>> previewPlayback(@PathVariable long spaceId,
                                                             @PathVariable long recordingId,
                                                             @RequestParam String mode) {
        return Response.builderSuccess(coreMockRuleClient.previewPlayback(recordingId, mode));
    }

    /**
 * Confirm-and-file the replay plan : write permission —
 * rules land in the space through core's single import channel; the
 * idempotence skip list and accumulated promoted_count come back from
 * core untouched.
 */
    @PostMapping("/{recordingId}/playback")
    @RequirePermission("perm:mock:write")
    public Response<MockPlaybackResultView> playback(@PathVariable long spaceId,
                                                     @PathVariable long recordingId,
                                                     @RequestParam String mode) {
        return Response.builderSuccess(coreMockRuleClient.playback(spaceId, recordingId, mode));
    }
}

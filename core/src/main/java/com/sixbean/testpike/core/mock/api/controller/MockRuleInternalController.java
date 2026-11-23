package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.mock.api.dto.MockHitPageView;
import com.sixbean.testpike.core.mock.api.dto.MockServingEndpointPageView;
import com.sixbean.testpike.core.mock.api.dto.MockPlaybackResult;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingInfo;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingPageView;
import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInfo;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioPageView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubPageView;
import com.sixbean.testpike.core.mock.domain.component.ExportDialectResult;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.service.MockDialectExportService;
import com.sixbean.testpike.core.mock.domain.service.MockHitPromoteService;
import com.sixbean.testpike.core.mock.domain.service.MockHitQueryService;
import com.sixbean.testpike.core.mock.domain.service.MockPlaybackService;
import com.sixbean.testpike.core.mock.domain.service.MockRecordingService;
import com.sixbean.testpike.core.mock.domain.service.MockScenarioService;
import com.sixbean.testpike.core.mock.domain.service.MockServingInventoryService;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubImportService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mock rule management-plane internal endpoints (core service layer; thin —
 * parameter intake + wrap only, all semantics live in the services).
 * <p>Consumers: the admin bridge (permission keys land there, pack C) and the
 * mock-server RuleLoader, which polls /internal/mock/snapshot by routing handle
 * and pins on the returned content version (pull model by design — core never
 * pushes rule updates to the traffic plane).
 */
@RestController
@RequestMapping("/internal/mock")
public class MockRuleInternalController {

    private final MockSpaceService spaceService;
    private final MockStubService stubService;
    private final MockHitQueryService hitQueryService;
    private final MockHitPromoteService hitPromoteService;
    private final MockStubImportService stubImportService;
    private final MockScenarioService scenarioService;
    private final MockRecordingService recordingService;
    private final MockPlaybackService playbackService;
    private final MockDialectExportService dialectExportService;
    private final MockServingInventoryService servingInventoryService;

    public MockRuleInternalController(MockServingInventoryService servingInventoryService,
                                      MockSpaceService spaceService, MockStubService stubService,
                                      MockHitQueryService hitQueryService,
                                      MockHitPromoteService hitPromoteService,
                                      MockStubImportService stubImportService,
                                      MockScenarioService scenarioService,
                                      MockRecordingService recordingService,
                                      MockPlaybackService playbackService,
                                      MockDialectExportService dialectExportService) {
        this.spaceService = spaceService;
        this.stubService = stubService;
        this.hitQueryService = hitQueryService;
        this.hitPromoteService = hitPromoteService;
        this.stubImportService = stubImportService;
        this.scenarioService = scenarioService;
        this.recordingService = recordingService;
        this.playbackService = playbackService;
        this.dialectExportService = dialectExportService;
        this.servingInventoryService = servingInventoryService;
    }

    /**
 * Create a mock space under a project (production-env ban in the save
 * chain). ttlHours (CI doors, stamps a throwaway expiry the
 * scheduled sweep later honours; omitted = never expires (UI posture).
 */
    @PostMapping("/space/create")
    public Response<Long> createSpace(@RequestParam long projectId,
                                      @RequestParam(required = false) Integer ttlHours,
                                      @RequestBody MockSpaceInput input) {
        return Response.builderSuccess(spaceService.createWithTtl(projectId, input, ttlHours));
    }

    /**
     * Lifecycle transition (draft→publish→retire). Thin intake — the edge set,
     * optimistic lock and publish-time prod-env re-check all live in the
     * service, so the admin bridge and any other caller speak one spectrum.
     */
    @PostMapping("/space/transition")
    public Response<MockSpaceInfo> transitionSpace(@RequestParam long spaceId,
                                                   @RequestParam int targetStatus,
                                                   @RequestParam int expectedVersion) {
        return Response.builderSuccess(spaceService.transition(spaceId, targetStatus, expectedVersion));
    }

    /**
 * Miss-policy door : save the space's own choice of what an
 * unmatched request does. Thin intake — rung-name legality rides the shared
 * MockMissPolicies spectrum inside the service, the optimistic lock guards
 * concurrent editors, and the version bump re-pins the content digest so
 * the traffic plane picks the new ladder up on its next poll.
 */
    @PostMapping("/space/policy")
    public Response<MockSpaceInfo> updateSpaceMissPolicy(@RequestParam long spaceId,
                                                          @RequestParam String missPolicy,
                                                          @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                spaceService.updateMissPolicy(spaceId, missPolicy, expectedVersion));
    }

    /** Spaces of one project in routing order. */
    @GetMapping("/space/list")
    public Response<List<MockSpaceInfo>> listSpaces(@RequestParam long projectId) {
        return Response.builderSuccess(spaceService.listByProject(projectId));
    }

    /**
 * One space by id : the reverse-lookup anchor MCP callers use
 * to resolve a space's owning project before the project-member gate —
 * management-plane read, ghost id refused by the service in plain words.
 */
    @GetMapping("/space/get")
    public Response<MockSpaceInfo> getSpace(@RequestParam long spaceId) {
        return Response.builderSuccess(spaceService.get(spaceId));
    }

    /** Version-pinned snapshot for the mock-server loader (disabled rules excluded). */
    @GetMapping("/snapshot")
    public Response<MockRuleSnapshotView> snapshot(@RequestParam String spaceKey) {
        return Response.builderSuccess(spaceService.snapshot(spaceKey));
    }

    /** Create a stub rule under a space (save-chain validation single point). */
    @PostMapping("/stub/create")
    public Response<Long> createStub(@RequestParam long spaceId, @RequestBody MockStubInput input) {
        return Response.builderSuccess(stubService.create(spaceId, input));
    }

    /**
     * Edit one rule in place (same save-chain gates as create, optimistic lock
     * echo on the payload). Thin intake — ordering, validation, provenance
     * immutability all live in the service.
     */
    @PostMapping("/stub/update")
    public Response<MockStubInfo> updateStub(@RequestParam long stubId,
                                             @RequestBody MockStubInput input) {
        return Response.builderSuccess(stubService.update(stubId, input));
    }

    /** Remove one rule (traffic plane stops serving it on the next poll). */
    @DeleteMapping("/stub/delete")
    public Response<Void> deleteStub(@RequestParam long stubId) {
        stubService.delete(stubId);
        return Response.builderSuccess(null);
    }

    /** Matched-serve count of one rule over the last N days (impact preview). */
    @GetMapping("/stub/recent-hits")
    public Response<Long> stubRecentHits(@RequestParam long stubId,
                                         @RequestParam(defaultValue = "7") int days) {
        return Response.builderSuccess(stubService.recentHitCount(stubId, Math.max(1, days)));
    }

    /**
     * Project-wide serving inventory: every mock endpoint across the project's
     * spaces in routing order, with the 7-day hit count per row (the flat
     * cross-space list the per-space doors structurally cannot answer).
     */
    @GetMapping("/serving-inventory/page")
    public Response<MockServingEndpointPageView> servingInventoryPage(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(servingInventoryService.listProjectEndpoints(
                projectId, page, pageSize, status, method, keyword, sortBy, sortDir));
    }

    /**
     * Contract-direct preview: the stored endpoint snapshot of a spec version
     * row replays through the deterministic mapper (zero tokens) — a draft is
     * a proposal for the import wizard, nothing is persisted here.
     */
    @GetMapping("/stub/drafts")
    public Response<List<MockStubDraft>> stubDrafts(@RequestParam long specId) {
        return Response.builderSuccess(stubImportService.previewDrafts(specId));
    }

    /**
     * Contract-direct import: drafts land through the normal stub save chain
     * (validation single point untouched); rules the space already carries for
     * the same method+path are skipped, so re-import stays idempotent.
     */
    @PostMapping("/stub/import")
    public Response<MockStubImportResult> importStubs(@RequestParam long spaceId,
                                                      @RequestBody List<MockStubDraft> drafts) {
        return Response.builderSuccess(stubImportService.importDrafts(spaceId, drafts));
    }

    /** Rules of one space in evaluation order. */
    @GetMapping("/stub/list")
    public Response<List<MockStubInfo>> listStubs(@RequestParam long spaceId) {
        return Response.builderSuccess(stubService.listBySpace(spaceId));
    }

    /**
 * One paged window of the space's rules in evaluation order
 * — display-scale iron law: admin-plane list doors must carry page/pageSize).
 * Paging clamp and the ghost-space refusal live in the service; the full
 * /stub/list above stays for the snapshot loader and editor picklists.
 */
    @GetMapping("/stub/page")
    public Response<MockStubPageView> pageStubs(@RequestParam long spaceId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(stubService.listBySpacePaged(spaceId, page, pageSize));
    }

    /**
 * File a stateful scenario declaration under a space . The machine
 * shape is gated by the shared declaration kernel at the save chain; stub
 * rows later bind to it through the same kernel's declared-state check.
 */
    @PostMapping("/scenario/create")
    public Response<Long> createScenario(@RequestParam long spaceId,
                                         @RequestBody MockScenarioInput input) {
        return Response.builderSuccess(scenarioService.create(spaceId, input));
    }

    /** Scenario declarations of one space in name order. */
    @GetMapping("/scenario/list")
    public Response<List<MockScenarioInfo>> listScenarios(@RequestParam long spaceId) {
        return Response.builderSuccess(scenarioService.listBySpace(spaceId));
    }

    /**
 * One paged window of the space's scenarios the new
 * /mock/scenarios list page reads its windows through this door; clamp and
 * ghost-space refusal stay in the service, the full /scenario/list above
 * stays for the stub editor picklists consumers). adds the
 * whitelisted sortBy/sortDir pair — empty keeps the historical name order.
 */
    @GetMapping("/scenario/page")
    public Response<MockScenarioPageView> pageScenarios(@RequestParam long spaceId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize,
                                                        @RequestParam(required = false) String sortBy,
                                                        @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                scenarioService.listBySpacePaged(spaceId, page, pageSize, sortBy, sortDir));
    }

    /**
     * One hit-ledger inspector page for a space, newest serve-time first.
     * matched omitted = both spectra; unknown space id is refused by core
     * instead of answering a pretend-empty page.
     */
    @GetMapping("/hit/list")
    public Response<MockHitPageView> listHits(@RequestParam long spaceId,
                                              @RequestParam(required = false) Boolean matched,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              @RequestParam(required = false) String sortBy,
                                              @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                hitQueryService.listBySpace(spaceId, matched, page, pageSize, sortBy, sortDir));
    }

    /**
 * Hit-ledger row count for a space (CI verdict door, : "did my
 * run actually hit the mock", counted from an ISO-8601 {@code since}
 * instant when given. matched omitted = both spectra. A ghost space is
 * refused in plain words — a friendly zero would let a typo'd space id
 * pass a pipeline gate.
 */
    @GetMapping("/hit/count")
    public Response<Long> countHits(@RequestParam long spaceId,
                                    @RequestParam(required = false)
                                    java.time.LocalDateTime since,
                                    @RequestParam(required = false) Boolean matched) {
        return Response.builderSuccess(hitQueryService.countBySpace(spaceId, since, matched));
    }

    /**
 * Open a cassette box for a published space . All gates —
 * lifecycle state, target shape, allowlist fail-closed, one-open-box —
 * live in the recording service; this endpoint is thin intake only.
 */
    @PostMapping("/recording/start")
    public Response<Long> startRecording(@RequestParam long spaceId,
                                         @RequestParam String targetBaseUrl) {
        return Response.builderSuccess(recordingService.start(spaceId, targetBaseUrl));
    }

    /** Seal an open box (recording→stopped with ended_at; double-seal refused). */
    @PostMapping("/recording/stop")
    public Response<MockRecordingInfo> stopRecording(@RequestParam long recordingId) {
        return Response.builderSuccess(recordingService.stop(recordingId));
    }

    /** Boxes of one space, newest first (ghost space refused, not empty-listed). */
    @GetMapping("/recording/list")
    public Response<List<MockRecordingInfo>> listRecordings(@RequestParam long spaceId) {
        return Response.builderSuccess(recordingService.listBySpace(spaceId));
    }

    /**
 * One paged window of the space's boxes display-scale +
 * sort contract: /mock/spaces/{sid}/recordings/page consumes this door;
 * clamp, whitelist order and the truthful total are core semantics. The
 * unbounded list above stays for internal consumers like the promotion
 * counters).
 */
    @GetMapping("/recording/page")
    public Response<MockRecordingPageView> pageRecordings(@RequestParam long spaceId,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int pageSize,
                                                          @RequestParam(required = false) String sortBy,
                                                          @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                recordingService.listBySpacePaged(spaceId, page, pageSize, sortBy, sortDir));
    }

    /**
 * Cassette replay preview : the box's recorded frames
 * (live hold while open, sealed cassette after) mapped into save-ready
 * stub drafts under strict|fuzzy — nothing is persisted here, a draft is
 * a proposal for the confirmation drawer.
 */
    @GetMapping("/recording/preview")
    public Response<List<MockStubDraft>> previewPlayback(@RequestParam long recordingId,
                                                         @RequestParam String mode) {
        return Response.builderSuccess(playbackService.previewDrafts(recordingId, mode));
    }

    /**
 * Cassette replay playback : the replay plan files through
 * the single stub-import channel (normal save chain, idempotent) and the
 * box's promoted_count accumulates; space/box mismatch is refused loudly.
 */
    @PostMapping("/recording/playback")
    public Response<MockPlaybackResult> playback(@RequestParam long spaceId,
                                                 @RequestParam long recordingId,
                                                 @RequestParam String mode) {
        return Response.builderSuccess(playbackService.playback(spaceId, recordingId, mode));
    }

    /**
 * Masked frame sample of one box for the A-4 AI induction leg (
 * ; ai-service CoreGateway.getMockRecordingFrames is the consumer).
 * The cap (K8 key mock.ai.summarize.max-sample-frames, default 50) and the
 * box-state-honest read spectrum live in the playback service — thin intake
 * only, same posture as the preview/playback doors above.
 */
    @GetMapping("/recording/frames")
    public Response<List<MockRecordingFrameMessage>> recordingFrames(
            @RequestParam long recordingId) {
        return Response.builderSuccess(playbackService.sampleFrames(recordingId));
    }

    /**
 * Inspector promote : one answered hit-ledger row becomes
 * one exact stub through the shared draft mapper and the single import
 * channel — the "make this request a stub" one click. Rows without a
 * response plane and cross-space/ghost ids are refused by the service in
 * plain words; a re-click on a covered method+path skips idempotently.
 */
    @PostMapping("/hit/promote")
    public Response<MockStubImportResult> promoteHit(@RequestParam long spaceId,
                                                     @RequestParam long hitId) {
        return Response.builderSuccess(hitPromoteService.promote(spaceId, hitId));
    }

    /**
 * Dialect export of one space :
 * format=mockoon renders a Mockoon document, format=wiremock renders
 * WireMock stub mappings — both carry the space routing root so the file
 * answers on the same URL as the live mock. The honesty notes ride along:
 * every rule feature the target dialect cannot express is named per stub,
 * never silently dropped. Export is a management-plane read (draft spaces
 * included), unlike the publication-gated traffic snapshot.
 */
    @GetMapping("/space/export")
    public Response<ExportDialectResult> exportSpace(@RequestParam long spaceId,
                                                     @RequestParam String format) {
        return Response.builderSuccess(dialectExportService.export(spaceId, format));
    }
}

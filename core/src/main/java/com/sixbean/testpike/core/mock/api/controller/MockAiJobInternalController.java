package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockGenMessage;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobDraftItemView;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobEstimateView;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobInfo;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobStatusUpdate;
import com.sixbean.testpike.core.mock.api.dto.MockDraftArtifactGrantView;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.service.MockAiJobService;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI mock-drafting job internal endpoints (core control plane, ).
 * <p>Consumers: the admin bridge (permission keys land there — core never
 * faces users) and the ai-service draft runner, which pulls the job row, the
 * pinned budget snapshot and the prompt over these doors instead of trusting
 * a fat MQ payload.
 * <p>Create doubles as the MQ publish point: after the service commits the
 * 'queued' row, one ai.mock.gen message fires here (ExploreInternalController
 * precedent — publishing inside the service is forbidden). The payload is
 * identity-only; projectId comes from the space row, never from a request
 * context guessed across services.
 */
@RestController
@RequestMapping("/internal/mock/jobs")
public class MockAiJobInternalController {

    private final MockAiJobService aiJobService;
    private final MockSpaceService spaceService;
    private final AiGenPublisher aiGenPublisher;
    private final com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader taskEvents;

    public MockAiJobInternalController(MockAiJobService aiJobService,
                                       MockSpaceService spaceService,
                                       AiGenPublisher aiGenPublisher,
                                       com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader taskEvents) {
        this.aiJobService = aiJobService;
        this.spaceService = spaceService;
        this.aiGenPublisher = aiGenPublisher;
        this.taskEvents = taskEvents;
    }

    /**
     * R62 cursor window over the business-step ledger of one bulk enrichment
     * run (job kind mock-spec-enrich, job_ref = job id). Frames are landed by
     * the ai-service executor through the shared process ledger door; this read
     * resolves the newest run anchor from the ledger itself, so a re-run of the
     * same job tails its latest attempt without any client-side token. The job
     * row is read first: an unknown id refuses with the honest not-found
     * wording before the ledger is touched (same posture as the batch tail
     * door; membership guarding lives on the admin bridge).
     */
    @GetMapping("/{id}/process-events")
    public Response<List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>> processEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        aiJobService.get(id);
        return Response.builderSuccess(taskEvents.sinceLatestRun(
                com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_MOCK_SPEC_ENRICH,
                String.valueOf(id), cursor, limit));
    }

    /**
     * Create a drafting job behind the gates and fire the ai.mock.gen dispatch
     * from this publish point (service never publishes). A gate refusal
     * throws before any send, so a refused job never reaches the queue. The
     * projectId anchor is re-read from the space row — never guessed from a
     * cross-service request context.
     */
    @PostMapping
    public Response<MockAiJobInfo> create(@RequestBody MockAiJobCreateInput input) {
        MockAiJobInfo job = aiJobService.createJob(input);
        Long operatorId = OperatorContext.currentOperatorId();
        long projectId = spaceService.get(job.spaceId()).projectId();
        aiGenPublisher.publishMockGen(messageFor(job, projectId,
                operatorId == null ? 0L : operatorId));
        return Response.builderSuccess(job);
    }

    /** Cost estimate of a stored job (pure: writes nothing, calls no model). */
    @PostMapping("/{id}/estimate")
    public Response<MockAiJobEstimateView> estimate(@PathVariable long id) {
        return Response.builderSuccess(aiJobService.estimatePreview(id));
    }

    /** One job row (ghost id refused in plain words — the consumer's pull door). */
    @GetMapping("/{id}")
    public Response<MockAiJobInfo> get(@PathVariable long id) {
        return Response.builderSuccess(aiJobService.get(id));
    }

    /** Jobs of one space, newest first (list door of the bridge). */
    @GetMapping
    public Response<List<MockAiJobInfo>> list(@RequestParam long spaceId) {
        return Response.builderSuccess(aiJobService.listBySpace(spaceId));
    }

    /**
     * Consumer status write-back (ai-service runner → control plane): the thin
     * dispatch maps the requested target status onto its transition method —
     * the spectrum itself (queued→running, running→preview/failed, terminal
     * sealed) is guarded inside the service, never duplicated here. Unknown
     * targets are refused in plain words so a malformed consumer payload fails
     * loudly instead of silently sealing the wrong column.
     */
    @PostMapping("/{id}/status")
    public Response<MockAiJobInfo> updateStatus(@PathVariable long id,
                                                @RequestBody MockAiJobStatusUpdate update) {
        String status = update.status() == null ? "" : update.status().trim();
        return switch (status) {
            case "running" -> Response.builderSuccess(aiJobService.markRunning(id));
            case "preview" -> Response.builderSuccess(
                    aiJobService.markPreview(id, update.resultPreviewRef()));
            case "failed" -> Response.builderSuccess(
                    aiJobService.markFailed(id, update.errorMessage()));
            default -> throw new ServiceException("不支持的任务状态回写「" + status
                    + "」：消费者可回写的目标为 running / preview / failed",
                    BizErrorCode.VALIDATION_ERROR);
        };
    }

    /**
     * Draft-bundle upload grant (consumer's artifact door): presigned PUT for
     * the model output so the 300-char preview-reference column only ever
     * stores the object key. Ghost job refused in plain words by the service.
     */
    @GetMapping("/{id}/artifact-grant")
    public Response<MockDraftArtifactGrantView> grantArtifact(@PathVariable long id,
                                                              @RequestParam String fileName) {
        return Response.builderSuccess(aiJobService.artifactGrant(id, fileName));
    }

    /**
     * Draft read-back (the confirmation drawer's item door): typed items of
     * the stored bundle. State spectrum, missing artifact and malformed bundle
     * refusals are the service's single point — the door only wraps the ok
     * envelope.
     */
    @GetMapping("/{id}/draft-items")
    public Response<List<MockAiJobDraftItemView>> draftItems(@PathVariable long id) {
        return Response.builderSuccess(aiJobService.previewDraftItems(id));
    }

    /** Human-ticked drafts into the rule source (the only AI ingress). */
    @PostMapping("/{id}/confirm")
    public Response<List<MockStubInfo>> confirm(@PathVariable long id,
                                                @RequestBody List<MockStubInput> selectedStubs) {
        return Response.builderSuccess(aiJobService.confirm(id, selectedStubs));
    }

    /** Throw a live job away (terminal states refused — history is sealed). */
    @DeleteMapping("/{id}")
    public Response<MockAiJobInfo> ignore(@PathVariable long id) {
        return Response.builderSuccess(aiJobService.ignore(id));
    }

    /** Per-trigger dedup token of the ai.mock.gen payload (consumer skips duplicates). */
    static MockGenMessage messageFor(MockAiJobInfo job, long projectId, long operatorId) {
        return new MockGenMessage(job.id(), job.spaceId(), projectId, job.jobType(),
                operatorId, java.util.UUID.randomUUID().toString());
    }
}

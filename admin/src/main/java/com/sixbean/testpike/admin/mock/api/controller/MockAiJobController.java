package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockAiJobCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobDraftItemView;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobEstimateView;
import com.sixbean.testpike.admin.mock.api.dto.MockAiJobView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubInput;
import com.sixbean.testpike.admin.mock.api.dto.MockStubView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
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
 * AI mock-drafting job admin bridge : the user-facing doors of the
 * drafting ledger.
 * <p>Permission keys follow the stub-bridge audience split: reads
 * (list/detail/estimate) open with perm:mock:read, the drafting verbs
 * (create/confirm/ignore) with perm:mock:write — no new keys, reused from the
 * catalog scan.
 * <p>Thin bridge (red line 6): every gate — master AND sub-switch matrix,
 * prompt shape, job-type domain, preview-only confirm, sealed-terminal ignore
 * — is the core MockAiJobService's single point; refusals propagate with
 * their plain wording untouched and the bridge files zero validation.
 */
@RestController
@RequestMapping("/api/v1/mock/jobs")
public class MockAiJobController {

    private final CoreMockRuleClient coreMockRuleClient;

    public MockAiJobController(CoreMockRuleClient coreMockRuleClient) {
        this.coreMockRuleClient = coreMockRuleClient;
    }

    /** Drafting jobs of one space, newest first (empty list = honest empty state). */
    @GetMapping
    @RequirePermission("perm:mock:read")
    public Response<List<MockAiJobView>> list(@RequestParam long spaceId) {
        return Response.builderSuccess(coreMockRuleClient.listAiJobs(spaceId));
    }

    /**
     * File a drafting request behind the AI gates. A closed switch comes back
     * as FORBIDDEN naming the exact config key to open — never a silent no-op.
     */
    @PostMapping
    @RequirePermission("perm:mock:write")
    public Response<MockAiJobView> create(@RequestBody MockAiJobCreateRequest request) {
        return Response.builderSuccess(coreMockRuleClient.createAiJob(request));
    }

    /** One job's detail (task card reads status + pinned budget + error wording). */
    @GetMapping("/{id}")
    @RequirePermission("perm:mock:read")
    public Response<MockAiJobView> get(@PathVariable long id) {
        return Response.builderSuccess(coreMockRuleClient.getAiJob(id));
    }

    /** Pre-run cost estimate — arithmetic over the stored prompt, nothing burns. */
    @PostMapping("/{id}/estimate")
    @RequirePermission("perm:mock:read")
    public Response<MockAiJobEstimateView> estimate(@PathVariable long id) {
        return Response.builderSuccess(coreMockRuleClient.estimateAiJob(id));
    }

    /**
     * Drafted items read back from the job's stored bundle (the confirmation
     * drawer's data door). Read-permission audience; every refusal (no
     * preview yet, artifact vanished, broken bundle) is core's single point
     * forwarded with its plain wording.
     */
    @GetMapping("/{id}/drafts")
    @RequirePermission("perm:mock:read")
    public Response<List<MockAiJobDraftItemView>> drafts(@PathVariable long id) {
        return Response.builderSuccess(coreMockRuleClient.listAiJobDrafts(id));
    }

    /**
     * Business-step tail of one bulk enrichment job (the drawer behind the
     * "execution process" entry): cursor window over the shared process
     * ledger, read-only, audience = perm:mock:read like its sibling doors.
     * Core resolves the newest run anchor; an empty window while the run has
     * not landed its first frame is a valid answer, never an error.
     */
    @GetMapping("/{id}/process-events")
    @RequirePermission("perm:mock:read")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView>> processEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(
                coreMockRuleClient.aiJobProcessEvents(id, cursor, limit));
    }

    /**
     * Land the human-ticked drafts into the rule source (the only AI ingress;
     * empty selection refused by core, provenance forced source=nl-ai).
     */
    @PostMapping("/{id}/confirm")
    @RequirePermission("perm:mock:write")
    public Response<List<MockStubView>> confirm(@PathVariable long id,
                                                @RequestBody List<MockStubInput> selectedStubs) {
        return Response.builderSuccess(coreMockRuleClient.confirmAiJob(id, selectedStubs));
    }

    /** Throw a live job away (terminal states keep their sealed history). */
    @DeleteMapping("/{id}")
    @RequirePermission("perm:mock:write")
    public Response<MockAiJobView> ignore(@PathVariable long id) {
        return Response.builderSuccess(coreMockRuleClient.ignoreAiJob(id));
    }
}

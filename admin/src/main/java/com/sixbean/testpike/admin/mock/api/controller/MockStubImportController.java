package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftAdviceView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubImportResultView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiSpecClient;
import com.sixbean.testpike.common.clientobject.Response;
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
 * Contract-direct stub import admin bridge path 1 of the
 * design: an uploaded OpenAPI contract becomes save-ready mock rules with zero
 * LLM tokens through core's deterministic mapper).
 * <p>Two doors, one wizard: GET drafts = the preview step (replays the stored
 * endpoint snapshot, persists nothing), POST import = the confirm step (each
 * draft goes through the normal stub save chain, same method+path already
 * covered by the space comes back skipped, so re-import is idempotent).
 * <p>Permission: both doors consume perm:mock:write (the import is a write
 * chain) plus the project-level guard resolved from the SPEC ROW's owning
 * project — never a frontend-passed project id (H1 isolation; same posture as
 * ApiSpecPreviewController.generate, single guard source, red line 6).
 * <p>Thin bridge: mapping, validation and idempotence all live in core — the
 * drafts pass through verbatim (no rewrite, no re-sort, no re-check).
 */
@RestController
@RequestMapping("/api/v1/mock")
public class MockStubImportController {

    private final CoreMockRuleClient coreMockRuleClient;
    private final CoreApiSpecClient coreApiSpecClient;
    private final ProjectAccessGuard guard;

    public MockStubImportController(CoreMockRuleClient coreMockRuleClient,
                                    CoreApiSpecClient coreApiSpecClient,
                                    ProjectAccessGuard guard) {
        this.coreMockRuleClient = coreMockRuleClient;
        this.coreApiSpecClient = coreApiSpecClient;
        this.guard = guard;
    }

    /**
     * Preview the contract-direct drafts for one spec version row (wizard step
     * one — a proposal, nothing written). The owning project of the spec row is
     * re-read from core before the guard runs, so tampering with ids cannot
     * borrow another project's contract.
     */
    @GetMapping("/specs/{specId}/stub-drafts")
    @RequirePermission("perm:mock:write")
    public Response<List<MockStubDraftView>> previewDrafts(@PathVariable long specId) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, coreApiSpecClient.get(specId).projectId());
        return Response.builderSuccess(coreMockRuleClient.previewStubDrafts(specId));
    }

    /**
     * Preview with advice joined (wizard step one, MJ P3): the deterministic
     * floor answers at once — the bridge never waits for an LLM and never
     * re-decides anything. spaceId is optional: without a target space the
     * rows honestly carry no advice (core shape), and the plain old
     * stub-drafts door stays untouched for existing callers.
     */
    @GetMapping("/specs/{specId}/stub-drafts-advised")
    @RequirePermission("perm:mock:write")
    public Response<List<MockStubDraftAdviceView>> previewDraftsAdvice(
            @PathVariable long specId,
            @RequestParam(required = false) Long spaceId) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, coreApiSpecClient.get(specId).projectId());
        return Response.builderSuccess(coreMockRuleClient.previewStubDraftsAdvice(specId, spaceId));
    }

    /**
     * Import the wizard's chosen drafts into a space (step two). specId rides
     * the query string purely so the guard can resolve the contract's owning
     * project; drafts arrive as the body in the exact shape the preview served.
     * An empty selection is refused at the door with a human 400 before core is
     * touched — an empty import would silently report "done" and teach users to
     * distrust the wizard.
     */
    @PostMapping("/spaces/{spaceId}/import-from-spec")
    @RequirePermission("perm:mock:write")
    public Response<MockStubImportResultView> importFromSpec(@PathVariable long spaceId,
                                                             @RequestParam long specId,
                                                             @RequestBody List<MockStubDraftView> drafts) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, coreApiSpecClient.get(specId).projectId());
        if (drafts == null || drafts.isEmpty()) {
            throw new ServiceException(
                    "契约直出导入必须至少勾选一条规则草稿（预览结果为空时请先核对契约端点）", 400);
        }
        return Response.builderSuccess(coreMockRuleClient.importStubs(spaceId, drafts));
    }
}

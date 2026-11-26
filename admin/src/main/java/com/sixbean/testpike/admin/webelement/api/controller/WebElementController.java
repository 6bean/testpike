package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.webelement.api.dto.CensusPostingBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.CreateWebElementRequest;
import com.sixbean.testpike.admin.webelement.api.dto.HealEvidencePage;
import com.sixbean.testpike.admin.webelement.api.dto.RenameWebElementRequest;
import com.sixbean.testpike.admin.webelement.api.dto.SurveyBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.SurveyEventsPage;
import com.sixbean.testpike.admin.webelement.api.dto.TriggerCensusPostingRequest;
import com.sixbean.testpike.admin.webelement.api.dto.TriggerSurveyRequest;
import com.sixbean.testpike.admin.webelement.api.dto.UpdateWebElementAnchorsRequest;
import com.sixbean.testpike.admin.webelement.api.dto.RevisionStormRow;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementHealthRow;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementLedgerView;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementPageGroupView;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreWebElementClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Web element ledger API (WE batch S0, "one redesign edits one row").
 * Permission: the interceptor uses the VIEW-family key the journey pages
 * already ship (template always passes, zero drift); the real gate is the
 * project lens guard below — reads need membership, writes need the manage
 * role on the row's own project (the row is re-read server-side, never
 * trusted from the client).
 */
@RestController
@RequestMapping("/api/v1/web-elements")
public class WebElementController {

    private final CoreWebElementClient client;
    private final ProjectAccessGuard guard;

    public WebElementController(CoreWebElementClient client, ProjectAccessGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /** Ledger rows of the current project segment, optionally one page. */
    @GetMapping
    @RequirePermission("perm:project:project-view")
    public Response<List<WebElementLedgerView>> list(@RequestParam long projectId,
                                                     @RequestParam(required = false) String pageKey) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.list(projectId, pageKey));
    }

    /**
     * Step-health readings for the ledger rows (red/yellow/green column).
     * The reading itself comes from core untouched — this door only gates.
     */
    @GetMapping("/health")
    @RequirePermission("perm:project:project-view")
    public Response<List<WebElementHealthRow>> health(@RequestParam long projectId,
                                                      @RequestParam(required = false) String pageKey) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.health(projectId, pageKey));
    }

    /** Revision storms of the project segment, one card per storming page. */
    @GetMapping("/storms")
    @RequirePermission("perm:project:project-view")
    public Response<List<RevisionStormRow>> storms(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.storms(projectId));
    }

    /** Page-folded ledger with lifecycle counts (the ledger page default view). */
    @GetMapping("/pages")
    @RequirePermission("perm:project:project-view")
    public Response<List<WebElementPageGroupView>> pages(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.pages(projectId));
    }

    /** One row with its ordered anchors. */
    @GetMapping("/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<WebElementLedgerView> get(@PathVariable long id) {
        WebElementLedgerView row = client.get(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(row);
    }

    /**
 * Heal evidence rows of one execution task (report step badges,
 * §8.4). Membership is guarded by the task's own project as resolved
 * server-side; an unknown task answers an empty page (zero rows, null
 * project) so the report surface never burns a second lookup.
 */
    @GetMapping("/heal-evidence")
    @RequirePermission("perm:project:project-view")
    public Response<HealEvidencePage> healEvidenceByTask(@RequestParam long taskId) {
        HealEvidencePage page = client.healEvidenceByTask(taskId);
        if (page.projectId() != null) {
            guard.assertCanView(UserContext.current(), page.projectId());
        }
        return Response.builderSuccess(page);
    }

    /** Recent heals of one ledger element, newest first (detail drawer). */
    @GetMapping("/{id}/heal-evidence")
    @RequirePermission("perm:project:project-view")
    public Response<HealEvidencePage> healEvidenceByElement(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        WebElementLedgerView row = client.get(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(client.healEvidenceByElement(id, page, pageSize));
    }

    /**
 * Survey process tail of one page-survey session (R62 segment c) — the
 * tail drawer's read door, read-only. Ownership gate = the project the
 * session row belongs to, resolved server-side by core and checked here
 * before any event reaches the browser (same posture as the steps tail:
 * login suffices, membership enforced in code, no second permission
 * model). An absent limit defers to the core hot key
 * ai.stream.chunk-size.
 */
    @GetMapping("/survey/{sessionId}/process-events")
    public Response<SurveyEventsPage> surveyProcessEvents(
            @PathVariable long sessionId,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        SurveyEventsPage tail = client.surveyProcessEvents(sessionId, cursor, limit);
        if (tail.projectId() != null) {
            guard.assertCanView(UserContext.current(), tail.projectId());
        }
        return Response.builderSuccess(tail);
    }

    /** Register a candidate element; duplicate (project, page, name) refuses. */
    @PostMapping
    @RequirePermission("perm:project:project-view")
    public Response<WebElementLedgerView> create(@RequestBody CreateWebElementRequest request) {
        if (request.projectId() != null) {
            guard.assertCanManage(UserContext.current(), request.projectId());
        }
        return Response.builderSuccess(client.create(request));
    }

    /** Replace the ordered anchor set (human-reviewed anchors; stale writes refuse). */
    @PutMapping("/{id}/anchors")
    @RequirePermission("perm:project:project-view")
    public Response<WebElementLedgerView> updateAnchors(@PathVariable long id,
                                                        @RequestBody UpdateWebElementAnchorsRequest request) {
        WebElementLedgerView row = client.get(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        return Response.builderSuccess(
                client.updateAnchors(id, request.anchors(), request.expectedRevision()));
    }

    /** Lifecycle move (confirm / mark stale / retire) with the same lock posture. */
    @PutMapping("/{id}/state")
    @RequirePermission("perm:project:project-view")
    public Response<WebElementLedgerView> changeState(@PathVariable long id,
                                                      @RequestParam int targetState,
                                                      @RequestParam int expectedRevision) {
        WebElementLedgerView row = client.get(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        return Response.builderSuccess(client.changeState(id, targetState, expectedRevision));
    }

    /**
 * Rename one element : the manage gate rides the row's
 * own project, and same-page name clashes / stale revision tokens refuse
 * core-side with the shared 40904 / 40901 postures.
 */
    @PutMapping("/{id}/name")
    @RequirePermission("perm:project:project-view")
    public Response<WebElementLedgerView> rename(@PathVariable long id,
                                                 @RequestBody RenameWebElementRequest request) {
        WebElementLedgerView row = client.get(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        return Response.builderSuccess(
                client.rename(id, request.name(), request.expectedRevision()));
    }

    /**
 * Manual "survey this page" trigger (WE-S4 segment P4). A survey writes
 * candidate rows into the ledger, so the manage gate on the caller's own
 * project segment must pass before the core door is reached; a missing
 * segment (no header selection) refuses with the same posture the create
 * door uses — never a projectless dispatch.
 */
    @PostMapping("/survey")
    @RequirePermission("perm:project:project-view")
    public Response<SurveyBridgeResult> survey(@RequestBody TriggerSurveyRequest request) {
        if (request == null || request.projectId() == null) {
            throw new ServiceException("请先在顶栏选择项目，再到「页面元素库」页点「测绘此页」重新发起整页测绘", BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(client.survey(request.projectId(), request.startUrl()));
    }

    /**
 * Census → ledger-creation cards trigger . Filing cards
 * is scoped to one project segment, so the manage gate on the body's own
 * anchor passes BEFORE the core door; the answer is the idempotency
 * account (filed / already pending / already in library), never a
 * business write — landing still rides the draft card human gate.
 */
    @PostMapping("/census/ledger-drafts")
    @RequirePermission("perm:project:project-view")
    public Response<CensusPostingBridgeResult> postCensusLedgerDrafts(
            @RequestBody TriggerCensusPostingRequest request) {
        requireCensusProject(request);
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(client.postCensusLedgerDrafts(request.projectId()));
    }

    /** Census → reference-replacement cards trigger , same gate posture. */
    @PostMapping("/census/script-ref-drafts")
    @RequirePermission("perm:project:project-view")
    public Response<CensusPostingBridgeResult> postCensusScriptRefDrafts(
            @RequestBody TriggerCensusPostingRequest request) {
        requireCensusProject(request);
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(client.postCensusScriptRefDrafts(request.projectId()));
    }

    /** Shared refusal for both census triggers: no lens selection = no projectless scan. */
    private static void requireCensusProject(TriggerCensusPostingRequest request) {
        if (request == null || request.projectId() == null) {
            throw new ServiceException("请先在顶栏选择项目，再到「页面元素库」页点「批量迁移入库」或「批量引用替换」重新发起存量普查迁移", BizErrorCode.VALIDATION_ERROR);
        }
    }
}

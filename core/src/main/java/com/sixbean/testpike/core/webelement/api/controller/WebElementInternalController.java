package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.webelement.api.dto.CensusPostingInput;
import com.sixbean.testpike.core.webelement.api.dto.CensusPostingView;
import com.sixbean.testpike.core.webelement.api.dto.ElementEvidenceGrantView;
import com.sixbean.testpike.core.webelement.api.dto.HealEvidencePage;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import com.sixbean.testpike.core.webelement.api.dto.SurveyEventsPage;
import com.sixbean.testpike.core.webelement.api.dto.SurveyResultView;
import com.sixbean.testpike.core.webelement.api.dto.SurveyTriggerInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementPageView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementRenameInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyCollector;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyDispatch;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusMigrationService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusScriptMigrationService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import com.sixbean.testpike.core.webelement.domain.component.WebElementEvidenceGrantQuery;
import com.sixbean.testpike.core.webelement.domain.component.StepHealEvidenceQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Web element ledger internal doors (core service layer, admin reaches these
 * through its own client). Membership checks stay on the admin side; the core
 * enforces the anchor value domain, the lifecycle table and the revision lock.
 */
@RestController
@RequestMapping("/internal/web-elements")
public class WebElementInternalController {

    private final WebElementService webElementService;
    private final WebElementEvidenceGrantQuery evidenceGrantQuery;
    private final StepHealEvidenceQuery healEvidenceQuery;
    private final WebElementSurveyDispatch surveyDispatch;
    private final TaskEventWriter taskEvents;
    private final AiGenPublisher aiGenPublisher;
    private final WebElementCensusMigrationService censusLedgerPosting;
    private final WebElementCensusScriptMigrationService censusScriptRefPosting;
    private final ExploreService exploreService;
    private final TaskEventWindowReader taskEventWindowReader;
    private final com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService retentionService;

    public WebElementInternalController(WebElementService webElementService,
                                        WebElementEvidenceGrantQuery evidenceGrantQuery,
                                        StepHealEvidenceQuery healEvidenceQuery,
                                        WebElementSurveyDispatch surveyDispatch,
                                        TaskEventWriter taskEvents,
                                        AiGenPublisher aiGenPublisher,
                                        WebElementCensusMigrationService censusLedgerPosting,
                                        WebElementCensusScriptMigrationService censusScriptRefPosting,
                                        ExploreService exploreService,
                                        TaskEventWindowReader taskEventWindowReader,
                                        com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService retentionService) {
        this.webElementService = webElementService;
        this.evidenceGrantQuery = evidenceGrantQuery;
        this.healEvidenceQuery = healEvidenceQuery;
        this.surveyDispatch = surveyDispatch;
        this.taskEvents = taskEvents;
        this.aiGenPublisher = aiGenPublisher;
        this.censusLedgerPosting = censusLedgerPosting;
        this.censusScriptRefPosting = censusScriptRefPosting;
        this.exploreService = exploreService;
        this.taskEventWindowReader = taskEventWindowReader;
        this.retentionService = retentionService;
    }

    /**
 * Manual retention sweep door : same posture as the
 * O10 report-retention internal run — L4/E2E drive the exact twin loop the
 * daily job runs, idempotent on a settled ledger.
 */
    @PostMapping("/retention/run")
    public Response<com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerRetentionResult>
            runRetention() {
        return Response.builderSuccess(retentionService.sweep());
    }

    /**
 * Manual "survey this page" door (WE-S4 segment P4). Forwards to the shared
 * dispatch component (same explore-create chain as the explore dialog —
 * no second INSERT path), then opens one web-element-survey process event
 * (job_ref = session id) so the tail drawer can replay who triggered
 * which page survey. A blank
 * url refuses here rather than dispatching a session that could never
 * collect anything (honest refusal, not a silent white page).
 */
    @PostMapping("/survey")
    public Response<SurveyResultView> survey(@RequestBody SurveyTriggerInput input) {
        String url = input == null || input.startUrl() == null ? "" : input.startUrl().strip();
        if (url.isEmpty()) {
            throw new ServiceException("测绘需要先给出页面地址（完整 URL，如 https://shop.example.com/orders）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long projectId = input == null ? null : input.projectId();
        if (projectId == null) {
            throw new ServiceException("请先选择项目再发起整页测绘", BizErrorCode.VALIDATION_ERROR);
        }
        long operatorId = OperatorContext.currentOperatorId() == null
                ? 0L : OperatorContext.currentOperatorId();
        long sessionId = surveyDispatch.dispatch(operatorId, projectId, url);
        // Same kickoff as ExploreInternalController.create: the session row is
        // created without an MQ hop inside the shared dispatch component, so the
        // door publishes ai.explore.run here (controller-layer send precedent —
        // without it the survey session would sit status=1 forever, found by the
        // P5 live drill: 0 candidates relayed).
        aiGenPublisher.publishExploreRun(new ExploreRunMessage(sessionId, projectId, operatorId));
        taskEvents.step(AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY, String.valueOf(sessionId),
                // R62 segment b contract: one session = one run tail, token is
                // the session id so later step/terminal frames join this tail.
                String.valueOf(sessionId),
                WebElementSurveyCollector.SURVEY_GOAL_PREFIX + "已发起｜" + url
                        + "｜正在采集页面元素，完成后自动归档（会话 " + sessionId
                        + "，操作人 " + operatorId + "）",
                "url=" + url + " sessionId=" + sessionId + " operatorId=" + operatorId);
        return Response.builderSuccess(new SurveyResultView(sessionId, url));
    }

    /**
 * Survey tail read door (R62 segment c): replay the web-element-survey
 * process stream of one explore session through the shared window reader
 * (same cursor contract as the extraction/steps tails, no second reader).
 * The owning project is read server-side from the session row — the
 * client never claims it — so the admin bridge can enforce membership
 * before events reach the browser. Unknown sessions refuse with 40401
 * from ExploreService.get before the ledger is touched.
 */
    @GetMapping("/survey/{sessionId}/process-events")
    public Response<SurveyEventsPage> surveyProcessEvents(
            @PathVariable long sessionId,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        ExploreSessionView session = exploreService.get(sessionId);
        // routing split: the site tail rides the dedicated kind — same
        // window reader and cursor contract, never a second read chain.
        String streamKind = WebElementSurveyCollector.isSiteSurveyGoal(session.getGoalText())
                ? AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY_SITE
                : AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY;
        List<TaskEventRow> events = taskEventWindowReader.since(
                streamKind,
                // R62 segment b single-tail contract: job_ref = run_token = session id.
                String.valueOf(sessionId), String.valueOf(sessionId), cursor, limit);
        return Response.builderSuccess(new SurveyEventsPage(session.getProjectId(), events));
    }

    /**
 * Evidence-plane baseline grant for the executor compare port
 * (design 8.1 scale B, piece 2b). One ledger row, live revision;
 * unknown ids and blank keys answer found=false — a missing baseline
 * must degrade the heal to a suggestion, never break the result relay.
 */
    @PostMapping("/evidence-grant")
    public Response<ElementEvidenceGrantView> evidenceGrant(
            @RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("elementId");
        long elementId = raw instanceof Number n ? n.longValue() : -1L;
        return Response.builderSuccess(evidenceGrantQuery.grant(elementId));
    }

    /** Register a candidate row; duplicate (project, page, name) refuses. */
    @PostMapping
    public Response<WebElementView> create(@RequestBody WebElementInput input) {
        return Response.builderSuccess(webElementService.create(input));
    }

    /**
 * Prompt-summary door (WE batch S5): confirmed rows of one project,
 * confidence desc, capped rows — the generation bridge's read side. The
 * limit clamps into 1..500 in the service; a blank pageKey answers the
 * whole project segment.
 */
    @GetMapping("/prompt-summary")
    public Response<List<WebElementView>> promptSummary(
            @RequestParam long projectId,
            @RequestParam(required = false) String pageKey,
            @RequestParam(defaultValue = "60") int limit) {
        return Response.builderSuccess(webElementService.promptSummary(projectId,
                Optional.ofNullable(pageKey).filter(s -> !s.isBlank()).orElse(null), limit));
    }

    /** Page roll-up with lifecycle counts, ordered by folded page key. */
    @GetMapping("/pages")
    public Response<List<WebElementPageView>> pages(@RequestParam long projectId) {
        return Response.builderSuccess(webElementService.pagesOfProject(projectId));
    }

    /** Project segment with optional page-key filter (literal route wins over /{id}). */
    @GetMapping
    public Response<List<WebElementView>> list(@RequestParam long projectId,
                                               @RequestParam(required = false) String pageKey) {
        return Response.builderSuccess(webElementService.listByProject(
                projectId, Optional.ofNullable(pageKey).filter(s -> !s.isBlank()).orElse(null)));
    }

    /** Single row; missing rows refuse with the shared not-found posture. */
    @GetMapping("/{id}")
    public Response<WebElementView> get(@PathVariable long id) {
        return Response.builderSuccess(webElementService.findById(id));
    }

    /** Replace the ordered anchor set; the revision token rides as a query parameter. */
    @PutMapping("/{id}/anchors")
    public Response<WebElementView> updateAnchors(@PathVariable long id,
                                                  @RequestParam int expectedRevision,
                                                  @RequestBody List<WebElementAnchorInput> anchors) {
        return Response.builderSuccess(webElementService.updateAnchors(id, anchors, expectedRevision));
    }

    /** Lifecycle move along the transition table; consumes the revision token. */
    @PutMapping("/{id}/state")
    public Response<WebElementView> changeState(@PathVariable long id,
                                                @RequestParam int targetState,
                                                @RequestParam int expectedRevision) {
        return Response.builderSuccess(webElementService.changeState(id, targetState, expectedRevision));
    }

    /**
 * Rename one element ; dispatch references resolve
 * on the new name from the next read onwards. The revision token rides
 * as a query parameter like the anchors door.
 */
    @PutMapping("/{id}/name")
    public Response<WebElementView> rename(@PathVariable long id,
                                           @RequestParam int expectedRevision,
                                           @RequestBody WebElementRenameInput input) {
        return Response.builderSuccess(webElementService.rename(id, input.name(), expectedRevision));
    }

    /**
 * Self-heal evidence rows of one execution task in step order (report
 * badge surface, §8.4). The owning project id rides along so the
 * admin side can guard membership in one round trip; an unknown task
 * answers an empty page.
 */
    @GetMapping("/heal-evidence")
    public Response<HealEvidencePage> healEvidenceByTask(@RequestParam long taskId) {
        return Response.builderSuccess(healEvidenceQuery.byTask(taskId));
    }

    /**
 * Recent heals of one ledger element, newest first, bounded paging
 * (detail drawer, §8.4 "the ledger does not copy numbers").
 */
    @GetMapping("/{id}/heal-evidence")
    public Response<HealEvidencePage> healEvidenceByElement(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(healEvidenceQuery.byElement(id, page, pageSize));
    }

    /**
 * Census → ledger-creation cards door . The
 * posting itself writes no business table: it files one PENDING
 * confirmation card per repeated inline element, and the generic draft
 * door stays the only write path (human gate, red line). Idempotent per
 * element — a re-run on unchanged state reports posted = 0.
 */
    @PostMapping("/census/ledger-drafts")
    public Response<CensusPostingView> postCensusLedgerDrafts(@RequestBody CensusPostingInput input) {
        long projectId = requireProject(input == null ? null : input.projectId(),
                "请先在顶栏选择项目，再发起批量迁移成账");
        var posting = censusLedgerPosting.postLedgerDrafts(projectId);
        return Response.builderSuccess(new CensusPostingView(posting.entries(), posting.posted(),
                posting.alreadyPending(), posting.alreadyLedgered(), 0, 0));
    }

    /**
 * Census → reference-replacement cards door .
 * One PENDING card per script still hand-writing a locator for an
 * element that already lives in the page element library; confirming a
 * card lifts the script one new version through the same repair landing
 * chain, with the pre-replacement copy riding inside the card as the
 * per-case rollback material.
 */
    @PostMapping("/census/script-ref-drafts")
    public Response<CensusPostingView> postCensusScriptRefDrafts(@RequestBody CensusPostingInput input) {
        long projectId = requireProject(input == null ? null : input.projectId(),
                "请先在顶栏选择项目，再发起批量引用替换");
        var posting = censusScriptRefPosting.postScriptRefDrafts(projectId);
        return Response.builderSuccess(new CensusPostingView(posting.entries(), posting.posted(),
                posting.alreadyPending(), 0, posting.scriptsScanned(), posting.skippedNotLedgered()));
    }

    /** Project anchor shared by both posting doors — honest refusal, never a projectless scan. */
    private static long requireProject(Long projectId, String refusal) {
        if (projectId == null || projectId <= 0) {
            throw new ServiceException(refusal, BizErrorCode.VALIDATION_ERROR);
        }
        return projectId;
    }
}

package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.webelement.api.dto.SiteSurveyDiffView;
import com.sixbean.testpike.core.webelement.api.dto.SiteSurveyRulesView;
import com.sixbean.testpike.common.webelement.SiteSurveyRunView;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.webelement.domain.component.SiteSurveyDiffProposal;
import com.sixbean.testpike.core.webelement.domain.component.SiteSurveyDispatch;
import com.sixbean.testpike.core.webelement.domain.component.SiteSurveyRunLedger;
import com.sixbean.testpike.core.webelement.domain.component.SurveyEnvelopeTiers;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyCollector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Whole-site survey rules door : the ai-service kernel reads the
 * dispatch-time frozen snapshot of the session's run before starting the
 * deterministic crawl. The snapshot carries the declared viewport tiers when
 * it has none of its own (SurveyEnvelopeTiers stays the one parsing source).
 * A session without a run row is refused with the guidance face — why the
 * crawl cannot start, where to re-dispatch, and which retention parameter
 * controls the history window — never a silent empty answer.
 */
@RestController
@RequestMapping("/internal/site-survey-runs")
public class SiteSurveyRunInternalController {

    private final SiteSurveyRunLedger ledger;
    private final SurveyEnvelopeTiers surveyEnvelopeTiers;
    private final SiteSurveyDispatch siteSurveyDispatch;
    private final SiteSurveyDiffProposal diffProposal;
    private final TaskEventWriter taskEvents;
    private final AiGenPublisher aiGenPublisher;

    public SiteSurveyRunInternalController(SiteSurveyRunLedger ledger,
                                           SurveyEnvelopeTiers surveyEnvelopeTiers,
                                           SiteSurveyDispatch siteSurveyDispatch,
                                           SiteSurveyDiffProposal diffProposal,
                                           TaskEventWriter taskEvents,
                                           AiGenPublisher aiGenPublisher) {
        this.ledger = ledger;
        this.surveyEnvelopeTiers = surveyEnvelopeTiers;
        this.siteSurveyDispatch = siteSurveyDispatch;
        this.diffProposal = diffProposal;
        this.taskEvents = taskEvents;
        this.aiGenPublisher = aiGenPublisher;
    }

    @GetMapping("/by-session/{sessionId}")
    public Response<SiteSurveyRulesView> rulesBySession(@PathVariable("sessionId") long sessionId) {
        String snapshot = ledger.rulesSnapshotText(sessionId);
        if (snapshot == null) {
            throw new ServiceException(
                    "未找到本次整站测绘会话的派发规则记录（运行记录不存在，或已超过留存期）。"
                            + "请从「站点档案」页对该站点重新发起整站测绘；"
                            + "需要延长历史留存，可在系统设置参数配置中按名称 "
                            + "element.survey-site.event-retention-days 调整留存天数。",
                    BizErrorCode.NOT_FOUND);
        }
        return Response.builderSuccess(
                new SiteSurveyRulesView(surveyEnvelopeTiers.withDeclaredTiers(snapshot)));
    }

    /** Survey-record list segment for the profile drawer (newest first, capped). */
    @GetMapping
    public Response<List<SiteSurveyRunView>> listRecent(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return Response.builderSuccess(ledger.listRecent(projectId, capped));
    }

    /**
     * Re-survey review proposal (D6) for one run of one profile: pages new
     * since the previous finished run, pages that disappeared, and per-page
     * candidate deltas. Read-only — listing vanished pages never demotes
     * them; that stays behind the human approval gates. The project of the
     * run is re-read server-side and must equal the caller's claim.
     */
    @GetMapping("/{runId}/diff-proposal")
    public Response<SiteSurveyDiffView> diffProposal(
            @PathVariable("runId") long runId,
            @RequestParam long projectId) {
        Long profileId = ledger.profileOfRun(runId);
        if (profileId == null) {
            throw new ServiceException(
                    "该测绘记录不存在或已随档案删除，无法生成差异复核提案。"
                            + "请从「站点档案」抽屉的测绘记录清单中选择一条在档记录。",
                    BizErrorCode.NOT_FOUND);
        }
        ledger.assertRunWithinProject(runId, projectId);
        return Response.builderSuccess(diffProposal.proposal(profileId, runId));
    }

    /**
     * Dispatch door for the whole-site survey dialog. The operator resolves
     * from the forwarded header or the request context (audit closure); the
     * production guard, the rule freeze and the single-INSERT posture all stay
     * inside SiteSurveyDispatch — this door only adds the MQ hop and the
     * kickoff frame the tail replay needs, keyed by session id so the one
     * existing cursor read door serves the drawer (R40).
     */
    @PostMapping("/{profileId}/dispatch")
    public Response<Long> dispatchFromProfile(
            @RequestParam long projectId,
            @PathVariable long profileId,
            @RequestHeader(value = "X-Operator-Id", required = false) Long headerOperator) {
        long operatorId = headerOperator != null ? headerOperator
                : (OperatorContext.currentOperatorId() == null ? 0L : OperatorContext.currentOperatorId());
        long runId = siteSurveyDispatch.dispatch(operatorId, projectId, profileId);
        Long sessionId = ledger.sessionOfRun(runId);
        if (sessionId == null) {
            throw new ServiceException(
                    "整站测绘派发落账异常：运行行未绑定探索会话，请重新发起整站测绘；"
                            + "若反复出现，请到「系统设置 → 服务日志」按关键词 site-survey 查看派发记录。",
                    BizErrorCode.INTERNAL_ERROR);
        }
        aiGenPublisher.publishExploreRun(new ExploreRunMessage(sessionId, projectId, operatorId));
        taskEvents.step(AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY_SITE,
                String.valueOf(sessionId),
                String.valueOf(sessionId),
                WebElementSurveyCollector.SITE_SURVEY_GOAL_PREFIX + profileId
                        + "（会话 " + sessionId + "，操作人 " + operatorId + "）",
                "whole-site survey dispatch door; tail follows the one cursor contract");
        return Response.builderSuccess(runId);
    }
}

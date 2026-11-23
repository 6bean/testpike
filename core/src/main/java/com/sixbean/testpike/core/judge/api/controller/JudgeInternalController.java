package com.sixbean.testpike.core.judge.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.judge.api.dto.AdoptedCaseRaiseInput;
import com.sixbean.testpike.core.judge.api.dto.DispatchReceipt;
import com.sixbean.testpike.core.judge.api.dto.EvalSetBatchRaiseInput;
import com.sixbean.testpike.core.judge.api.dto.EvalSetPublishInput;
import com.sixbean.testpike.core.judge.api.dto.EvalSetRaiseInput;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.core.judge.api.dto.JudgeArtifactGrantView;
import com.sixbean.testpike.core.judge.api.dto.JudgeDispatchInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleCheckInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeRunPageView;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RecordView;
import com.sixbean.testpike.core.judge.api.dto.RubricFamilyView;
import com.sixbean.testpike.core.judge.api.dto.RunCreateInput;
import com.sixbean.testpike.core.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.core.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.core.judge.api.dto.RuleVerdict;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import com.sixbean.testpike.core.judge.api.dto.ScorecardTrendPointView;
import com.sixbean.testpike.core.judge.domain.service.JudgeDispatchService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRecordService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRubricService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRuleService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeSampleService;
import com.sixbean.testpike.core.judge.domain.service.JudgeScorecardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/judge")
public class JudgeInternalController {

    private final JudgeRunService runService;
    private final JudgeSampleService sampleService;
    private final JudgeRecordService recordService;
    private final JudgeRuleService ruleService;
    private final JudgeDispatchService dispatchService;
    private final JudgeScorecardService scorecardService;
    private final JudgeRubricService rubricService;

    public JudgeInternalController(JudgeRunService runService,
                                   JudgeSampleService sampleService,
                                   JudgeRecordService recordService,
                                   JudgeRuleService ruleService,
                                   JudgeDispatchService dispatchService,
                                   JudgeScorecardService scorecardService,
                                   JudgeRubricService rubricService) {
        this.runService = runService;
        this.sampleService = sampleService;
        this.recordService = recordService;
        this.ruleService = ruleService;
        this.dispatchService = dispatchService;
        this.scorecardService = scorecardService;
        this.rubricService = rubricService;
    }

    
    @GetMapping("/runs/{runId}")
    public Response<RunView> run(@PathVariable long runId) {
        return Response.builderSuccess(runService.detail(runId));
    }

    /**
     * Evaluation-run history page (AW4 facade; direct read — status gates,
     * anchor decisions and the 1..200 size bound all stay single-source in
     * JudgeRunService, this endpoint is a thin forwarder with zero
     * second-guessing).
     */
    @GetMapping("/runs")
    public Response<JudgeRunPageView> runs(@RequestParam(required = false) String evalsetName,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(runService.listRuns(evalsetName, status, page, size));
    }

    /**
     * Create an evaluation run (AW4; both anchors frozen at creation — the
     * unknown-rubric / unknown-set-version / digest-mismatch / illegal-target
     * rejections are core JudgeRunService's single-source decisions, this
     * endpoint only null-checks the body like the dispatch door precedent).
     */
    @PostMapping("/runs")
    public Response<RunView> createRun(@RequestBody RunCreateInput input) {
        if (input == null) {
            throw new ValidationException("评测运行创建请求体不能为空（需评分标准版本、评测集名与版本号、被评目标）");
        }
        return Response.builderSuccess(runService.createRun(input));
    }

    
    @GetMapping("/evalset/samples")
    public Response<List<SampleView>> samples(@RequestParam String setName,
                                              @RequestParam int version) {
        return Response.builderSuccess(sampleService.listSamples(setName, version));
    }

    /**
 * Enumerate every published evaluation-set version ( segment A read door
 * for the run-creation picker; rows are immutable version segments — this
 * endpoint forwards the enumeration, zero judgement of its own).
 */
    @GetMapping("/evalsets")
    public Response<List<EvalSetVersionView>> evalsets() {
        return Response.builderSuccess(sampleService.listSetVersions());
    }

    /**
 * Enumerate rubric family heads — latest version row per (name, domain)
 * ( segment A read door; the versioning/grayscale decision stays inside
 * JudgeRubricService save/cloneVersion, this is a thin forwarder).
 */
    @GetMapping("/rubrics")
    public Response<List<RubricFamilyView>> rubrics() {
        return Response.builderSuccess(rubricService.listLatest());
    }

    
    @PostMapping("/evalset/raise")
    public Response<EvalSetVersionView> raiseEvalSet(@RequestBody EvalSetRaiseInput input) {
        return Response.builderSuccess(sampleService.raiseFromTrainingSamples(
                input.setName(), input.sampleIds(), input.createdBy()));
    }

    
    @PostMapping("/evalset/raise-from-batch")
    public Response<EvalSetVersionView> raiseEvalSetFromBatch(
            @RequestBody EvalSetBatchRaiseInput input) {
        return Response.builderSuccess(sampleService.raiseFromBatchResults(
                input.setName(), input.taskIds(), input.createdBy()));
    }

    /**
 * Adopted excellent AI cases -> new golden-set version (, BA ruling 8:
 * the one-click collection entry that freezes hand-picked ledger cases into
 * the golden pack consumed by the drift replay). All gates (eligibility
 * triple, requirement anchor, versioning, snapshot digest) live single-source
 * in JudgeSampleService — this endpoint is a thin forwarder with zero
 * second-guessing (red line: no duplicate validation in the app layer).
 */
    @PostMapping("/evalset/raise-from-adopted")
    public Response<EvalSetVersionView> raiseEvalSetFromAdopted(
            @RequestBody AdoptedCaseRaiseInput input) {
        return Response.builderSuccess(sampleService.raiseFromAdoptedCases(
                input.setName(), input.caseIds(), input.createdBy()));
    }

    
    @PostMapping("/evalset/publish")
    public Response<EvalSetVersionView> publishEvalSet(@RequestBody EvalSetPublishInput input) {
        if (input == null) {
            throw new ValidationException("入库请求体不能为空（需评测集名与勾选样本清单）");
        }
        return Response.builderSuccess(sampleService.publish(
                input.setName(), input.samples(), input.createdBy()));
    }

    
    @PostMapping("/records")
    public Response<RecordView> record(@RequestBody RecordInput input) {
        return Response.builderSuccess(recordService.saveScore(input));
    }

    
    @GetMapping("/runs/{runId}/artifact-grant")
    public Response<JudgeArtifactGrantView> artifactGrant(@PathVariable long runId,
                                                          @RequestParam long sampleId) {
        return Response.builderSuccess(recordService.artifactGrant(runId, sampleId));
    }

 
    @PostMapping("/rule-check")
    public Response<RuleVerdict> ruleCheck(@RequestBody JudgeRuleCheckInput input) {
        return Response.builderSuccess(ruleService.check(input));
    }

    
    @GetMapping("/runs/{runId}/scorecard")
    public Response<RunScorecardView> scorecard(@PathVariable long runId) {
        return Response.builderSuccess(scorecardService.scorecard(runId));
    }

    
    @GetMapping("/scorecard/trend")
    public Response<List<ScorecardTrendPointView>> scorecardTrend(
            @RequestParam String rubricName, @RequestParam String rubricDomain,
            @RequestParam(defaultValue = "50") int limit) {
        return Response.builderSuccess(scorecardService.trend(rubricName, rubricDomain, limit));
    }

    
    @GetMapping("/scorecard/session-cards")
    public Response<List<GenerationSessionCardView>> sessionCards(
            @RequestParam String rubricName, @RequestParam String rubricDomain,
            @RequestParam(defaultValue = "50") int limit) {
        return Response.builderSuccess(
                scorecardService.sessionCards(rubricName, rubricDomain, limit));
    }

    
    @GetMapping("/runs/{runId}/sample-cards")
    public Response<RunSampleCardPageView> sampleCards(@PathVariable long runId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Response.builderSuccess(scorecardService.sampleCards(runId, page, size));
    }

    
    @GetMapping("/runs/diff")
    public Response<RunDiffView> diffRuns(@RequestParam long a, @RequestParam long b,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(scorecardService.diffRuns(a, b, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/runs/{runId}/cost-card")
    public Response<RunCostCardView> costCard(@PathVariable long runId) {
        return Response.builderSuccess(scorecardService.costCard(runId));
    }

    
    @PostMapping("/dispatch")
    public Response<DispatchReceipt> dispatch(@RequestBody JudgeDispatchInput input) {
        if (input == null) {
            throw new ValidationException("派发请求体不能为空（需 runId 与被评目标厂商可空位）");
        }
        return Response.builderSuccess(
                dispatchService.dispatch(input.runId(), input.evaluatedProvider()));
    }
}

package com.sixbean.testpike.admin.judge.infrastructure.remote.client;

import com.sixbean.testpike.admin.judge.api.dto.EvalSampleView;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetPublishBody;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionOptionView;
import com.sixbean.testpike.admin.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.admin.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRubricOptionView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeDispatchReceiptView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunCreateBody;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunPageView;
import com.sixbean.testpike.admin.judge.api.dto.JudgeRunView;
import com.sixbean.testpike.admin.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.admin.judge.api.dto.RunDiffView;
import com.sixbean.testpike.admin.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.admin.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.admin.judge.api.dto.ScorecardTrendPointView;

import java.util.List;


public interface CoreJudgeClient {

    
    EvalSetVersionView publish(EvalSetPublishBody body);

    
    RunSampleCardPageView sampleCards(long runId, int page, int size);

    /**
 * sortBy/sortDir optional: null pair = legacy sample-id ascending; the
 * whitelist gate lives in core, the bridge forwards without a second check.
 */
    RunDiffView diffRuns(long a, long b, int page, int size, String sortBy, String sortDir);

    
    RunCostCardView costCard(long runId);

    
    RunScorecardView scorecard(long runId);

    
    List<ScorecardTrendPointView> trend(String rubricName, String rubricDomain, int limit);

    
    List<GenerationSessionCardView> sessionCards(String rubricName, String rubricDomain, int limit);

    /**
     * Evaluation-set version sample list (AW1; direct read of core's samples
     * GET — set/version gates stay single-source in core, the bridge forwards
     * the rows untouched. Missing version = core rejection, rethrown same code).
     */
    List<EvalSampleView> samples(String setName, int version);

    /**
     * Promote training samples into a new set version (AW2 source ③; version
     * bump / missing ids / pairing gates single-source in core — thin forwarder).
     */
    EvalSetVersionView raise(String setName, List<Long> sampleIds, Long createdBy);

    /**
     * Promote batch task results into a new set version (AW2 source ②;
     * terminal-state gate single-source in core — thin forwarder).
     */
    EvalSetVersionView raiseFromBatch(String setName, List<Long> taskIds, Long createdBy);

    /**
     * Freeze adopted AI cases into a new golden-set version (AW2 source ①;
     * eligibility triple gate single-source in core — thin forwarder).
     */
    EvalSetVersionView raiseFromAdopted(String setName, List<Long> caseIds, Long createdBy);

    /**
     * Create an evaluation run, freezing both anchors (AW4; unknown rubric /
     * unknown set version / digest mismatch / illegal target rejections are
     * core's single-source decisions, rethrown with the same code).
     */
    JudgeRunView createRun(JudgeRunCreateBody body);

    /**
     * Dispatch a pending run to the judge queue (AW4; the four gates and
     * per-sample enqueue stay single-source in core JudgeDispatchService, the
     * receipt is echoed untouched).
     */
    JudgeDispatchReceiptView dispatch(long runId, String evaluatedProvider, Long operatorId);

    /**
     * Evaluation-run history page (AW4; newest first, size bound 1..200 is
     * core's decision, forwarded untouched).
     */
    JudgeRunPageView listRuns(String evalsetName, Integer status, int page, int size);

    /**
 * Enumerate every published evaluation-set version ( segment A picker
 * door; the version segments are immutable and enumerated by core —
 * thin forwarder, zero judgement).
 */
    List<EvalSetVersionOptionView> listEvalsetVersions();

    /**
 * Enumerate rubric family heads (latest version per name x domain;
 * segment A picker door — the versioning decision stays in core,
 * this bridge forwards the projection untouched).
 */
    List<JudgeRubricOptionView> listRubricFamilies();
}

package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.core.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.core.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.core.judge.api.dto.ScorecardTrendPointView;

import java.util.List;


public interface JudgeScorecardService {

    
    RunScorecardView scorecard(Long runId);

    
    List<ScorecardTrendPointView> trend(String rubricName, String rubricDomain, int limit);

    
    List<GenerationSessionCardView> sessionCards(String rubricName, String rubricDomain,
                                                 int limit);

    
    RunSampleCardPageView sampleCards(Long runId, int page, int size);

    
    default RunDiffView diffRuns(Long runAId, Long runBId, int page, int size) {
        return diffRuns(runAId, runBId, page, size, null, null);
    }

    /**
     * Six-arg diff with optional server-side ordering. sortBy/sortDir ride the
     * core SORT_COLUMNS whitelist (sample_id / score_a / score_b / delta) checked
     * fail-closed before any read; score columns are Java-side derived values of
     * the merged window, so ordering happens in memory over the whole union
     * before skip/limit — never in SQL. null/blank pair = legacy sample-id
     * ascending (zero drift).
     */
    RunDiffView diffRuns(Long runAId, Long runBId, int page, int size,
                         String sortBy, String sortDir);

    
    RunCostCardView costCard(Long runId);
}

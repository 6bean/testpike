package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.common.clientobject.ExploreRunStatsUpdate;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreCancelResult;
import com.sixbean.testpike.core.explore.api.dto.ExploreEstimate;
import com.sixbean.testpike.core.explore.api.dto.ExploreEvidenceGrantView;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepView;

import java.util.List;


public interface ExploreService {

    
    long create(long operatorId, long projectId, ExploreCreateInput input);

    
    ExploreEstimate estimate(long projectId, ExploreCreateInput input);

    
    ExploreSessionView get(long sessionId);

    
    default PageResult<ExploreSessionView> list(long projectId, int page, int size) {
        return list(projectId, page, size, null, null);
    }

    /**
     * Project-dimension list with an optional server-side sort pair: a
     * null/blank key keeps the historical id DESC default order, a whitelisted
     * key orders the whole window by that column with the id DESC tie-break
     * pinned, an off-list key is refused fail-closed by the shared gate.
     */
    PageResult<ExploreSessionView> list(long projectId, int page, int size, String sortBy, String sortDir);

    
    List<ExploreStepView> steps(long sessionId);

    
    String screenshotUrl(long sessionId, int seq);

    
    ExploreEvidenceGrantView grantEvidence(long sessionId, String kind, String fileName);

    
    long submitAct(long sessionId, String actionJson);

    
    long submitStandaloneAct(long projectId, String actionJson);

    
    List<Long> toCases(Long operatorId, long sessionId, List<Integer> seqs);

    
    ExploreCancelResult cancel(long sessionId);

    
    ExploreSessionView updateStatus(long sessionId, int status);

    
    ExploreSessionView updateStats(long sessionId, ExploreRunStatsUpdate stats);

    
    void recordStep(long sessionId, ExploreStepInput input);
}

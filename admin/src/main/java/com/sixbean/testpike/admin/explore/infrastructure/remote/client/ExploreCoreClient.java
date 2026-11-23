package com.sixbean.testpike.admin.explore.infrastructure.remote.client;

import com.sixbean.testpike.admin.explore.api.dto.ExploreCancelView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.admin.explore.api.dto.ExploreEstimateView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreStepView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreToCasesRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;

import java.util.List;


public interface ExploreCoreClient {

    
    ExploreSessionView create(long projectId, ExploreCreateInput input, long operatorId);

    
    ExploreEstimateView estimate(long projectId, ExploreCreateInput input);

    
    ExploreSessionView get(long sessionId);

    
    PageView<ExploreSessionView> list(long projectId, int page, int size, String sortBy, String sortDir);

    
    List<ExploreStepView> steps(long sessionId);

    /**
     * Cursor window of the session's process-event tail (R62 A-face). Rows keep
     * the shared shape of the generation/probe tails; the window size and the
     * clamping are core's single source.
     */
    List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView> processEvents(
            long sessionId, long cursor, Integer limit);

    
    String screenshotUrl(long sessionId, int seq);

    
    List<Long> toCases(long sessionId, ExploreToCasesRequest request, long operatorId);

    
    ExploreCancelView cancelSession(long sessionId);
}

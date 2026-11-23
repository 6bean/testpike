package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.JudgeRunPageView;
import com.sixbean.testpike.core.judge.api.dto.RunCreateInput;
import com.sixbean.testpike.core.judge.api.dto.RunView;


public interface JudgeRunService {

    
    RunView createRun(RunCreateInput input);

    
    RunView detail(Long id);

    /**
     * Evaluation-run history page (AW4 facade; direct read, newest first).
     * Filters optional: blank evalsetName = no filter, null status = all
     * states. Size bound 1..200 follows the TREND_LIMIT_MAX precedent —
     * an unbounded scan is rejected fail-closed, never silently clamped.
     */
    JudgeRunPageView listRuns(String evalsetName, Integer status, int page, int size);

    
    void start(Long id);

    
    void finish(Long id, boolean success);
}

package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.cases.api.dto.FlowGuideInfo;

/**
 * Case flow guide query (AO-9 E8): answers "the case is stuck at some status —
 * which actions can I take and where do I take them".
 */
public interface CaseFlowGuideService {

    /**
     * Build the guide.
     *
     * @param caseId live case to pin (null = whole-graph vocabulary answer)
     */
    FlowGuideInfo guide(Long caseId);
}

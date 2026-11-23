package com.sixbean.testpike.aiservice.perfdraft.domain.service;

import com.sixbean.testpike.aiservice.perfdraft.api.dto.PerfDraftRequest;

import java.util.Map;

/** Drafts a load-test scenario step list from one sentence (B-11, ai-service leg). */
public interface PerfDraftService {

    /**
     * Turn the request's sentence into a wizard-prefill draft.
     *
     * @return {@code {"steps":[...]}} with native JSON values, never persisted here
     */
    Map<String, Object> draft(PerfDraftRequest request);
}

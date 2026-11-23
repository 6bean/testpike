package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.linkage.api.dto.LinkageUrgeResult;

/**
 * Two stages mirror the Q4 review-reminder positions without a second
 * notification pipeline: past linkage.urge-hours the upstream creator (the
 * person who owns the grouped card) is reminded daily; past
 * linkage.escalate-hours the project owners are escalated (forced
 * subscription, like REVIEW_TIMEOUT). Marking/disclosure stays automatic;
 * the notification only points at the one-click card — no content of any
 * downstream object is ever touched here (red line intact).
 */
public interface LinkageUrgeService {

    /** Run one urge round (hourly scheduler and the run-now picker share this entry). */
    LinkageUrgeResult scan();
}

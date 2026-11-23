package com.sixbean.testpike.core.behavior.domain.service;

import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeAdvisory;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeEvent;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeStat;

import java.util.List;

/**
 * Result feedback loop for AI-produced material . Real user
 * verdicts — review pass/reject, verification-chip decisions, draft
 * accept/reject — are recorded once each and rolled up per prompt template
 * version into an observed result rate; comparing a gray-released candidate
 * against its control turns a material degradation into a rollback advisory.
 * The service only counts and advises; template status changes stay a human
 * action (red line: the write-confirmation gate is untouched).
 */
public interface BehaviorOutcomeService {

    /**
     * Records one verdict event. Replaying the same (source, sourceEventId)
     * is a no-op so retries and resyncs never double-count.
     *
     * @return true when the event was new, false when it was already on the ledger
     */
    boolean record(BehaviorOutcomeEvent event);

    /** Result-rate rollup per template version inside one domain (versions without rows absent). */
    List<BehaviorOutcomeStat> statsByTemplateVersion(String templateDomain);

    /**
     * Compares a candidate version against its control and reports whether the
     * observed drop exceeds the current tolerance (K8
     * ai.behavior.ab-roll-back-threshold, read fresh on every call).
     */
    BehaviorOutcomeAdvisory compareVersions(String templateDomain, long controlVersionId, long candidateVersionId);
}

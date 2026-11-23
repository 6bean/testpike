package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusReport;

/**
 * WE-S6 census door over persisted scripts (design A6): answers "which
 * hand-written inline locators does this project repeat across its web
 * cases, and how often" so the batch-migration drafts can work through the
 * highest-value elements first.
 */
public interface WebElementCensusService {

    /**
     * Survey the project's web case scripts and rank inline locators by
     * migration value. Read-only: produces ledger drafts nowhere.
     */
    WebElementCensusReport censusProject(Long projectId);
}

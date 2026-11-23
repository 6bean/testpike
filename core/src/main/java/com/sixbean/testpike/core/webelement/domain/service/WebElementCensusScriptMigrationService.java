package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.api.dto.WebElementScriptRefPosting;

/**
 * Script reference-replacement posting door (WE-S6 census migration,
 * piece 3b, design A6): turns the survey's per-script evidence for
 * elements that already live in the page element library into one pending
 * replacement card per referencing script. Filing cards never touches the
 * scripts — the confirmed card lands the new reference version through the
 * generic draft gate, exactly like every other AI write proposal.
 */
public interface WebElementCensusScriptMigrationService {

    /**
     * @param projectId concrete project anchor (blank values are refused)
     * @return the round's posting and exclusion counts (idempotency account)
     */
    WebElementScriptRefPosting postScriptRefDrafts(Long projectId);
}

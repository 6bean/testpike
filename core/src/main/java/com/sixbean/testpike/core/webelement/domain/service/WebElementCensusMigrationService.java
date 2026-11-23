package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerPosting;

/**
 * Census migration posting door : turns the
 * read-only survey of {@link WebElementCensusService} into one PENDING
 * confirmation card per repeated inline element. Filing cards never writes
 * the page element library and never rewrites scripts — the draft card
 * human gate stays the only write path (red line).
 */
public interface WebElementCensusMigrationService {

    /**
     * Survey the project and file the ledger-creation cards. Idempotent per
     * element: an entry that already has a pending card is counted, not
     * double-filed.
     */
    WebElementLedgerPosting postLedgerDrafts(Long projectId);
}

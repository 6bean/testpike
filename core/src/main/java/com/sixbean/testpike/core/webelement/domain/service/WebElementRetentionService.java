package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerRetentionResult;

/**
 * Ledger retention twin loop . Consumes the V544 seed
 * keys element.candidate-ttl-days (unverified candidates auto-retire after
 * the window) and element.retention-days (retired rows age out of the
 * ledger entirely), so the promise the config descriptions make is actually
 * kept and the table stops accumulating dead weight (R57).
 */
public interface WebElementRetentionService {

    /**
     * Run one full sweep: aged candidates first (state machine door, audit
     * kept), then physically purge retired rows past the retention window
     * together with their anchor stats and per-row ledger events. Idempotent
     * — a replay on a settled ledger changes nothing.
     */
    WebElementLedgerRetentionResult sweep();
}

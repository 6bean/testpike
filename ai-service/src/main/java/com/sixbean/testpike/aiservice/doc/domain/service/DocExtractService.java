package com.sixbean.testpike.aiservice.doc.domain.service;

import com.sixbean.testpike.common.mq.AiDocExtractMessage;


public interface DocExtractService {

    
    void extract(AiDocExtractMessage message);

    /**
     * DD batch item ㊀: terminal-failure write-back for extraction failures that
     * escaped the run's own catch faces (throws raised before the service body
     * opens, idempotency-aspect or lock outages) on the LAST retry attempt,
     * invoked by the MQ consumer right before the exception goes on to the
     * dead-letter exchange. Reuses the single failure seam — the absent-door
     * fidelity book plus the human-readable status write-back whose core-side
     * generation guard makes a redundant call a no-op. An entity-gone cause
     * short-circuits: a deleted document has no terminal row to write and its
     * dead ledger message must not be forced in. Never throws.
     */
    void handleTerminalFailure(AiDocExtractMessage message, Exception escapingFailure);
}

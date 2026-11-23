package com.sixbean.testpike.aiservice.doc.domain.component;

/**
 * one extraction generation currently being executed by THIS process.
 * The pair (documentId, extractToken) is the same generation identity the core
 * markFailed guard already validates, so a shutdown write-back can never touch a row
 * that a newer generation has taken over.
 */
public record DocExtractInFlightEntry(long documentId, String extractToken) {

    /** Registry key: document-scoped generation identity, no other consumer. */
    public String key() {
        return documentId + ":" + extractToken;
    }
}

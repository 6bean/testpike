package com.sixbean.testpike.aiservice.aichat.domain.service;

import java.time.OffsetDateTime;

/**
 * Ledger-side facts joined onto one knowledge row for grounding assembly:
 * the version anchor (audit) plus the last write time (AX batch item 6,
 * suspected-stale grade). Both sides are fail-soft — a doc missing from the
 * ledger read carries neither, which the kernel treats as "no timestamp,
 * never accused of staleness", never as evidence.
 *
 * @param revision current ledger revision of the document, null when the
 * ledger read was unavailable
 * @param updatedAt last write time of the document row, null when the row
 * has no timestamp in the read shape
 */
public record KnowledgeLedgerFacts(Integer revision, OffsetDateTime updatedAt) {
}

package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;

/**
 * Inspector promote door — the backend half of the request
 * inspector's "make this request a stub" one click. One answered ledger row
 * becomes one save-ready draft through the shared {@code HitDraftMapper} and
 * files through {@link MockStubImportService#importDrafts}: the normal stub
 * save chain with MockRuleValidator as its single validation point, so the
 * one click is a proposal channel, never a bypass (same posture as the
 * contract-direct and cassette playback doors).
 */
public interface MockHitPromoteService {

    /**
 * Promote one hit-ledger row of a space into a stub rule.
 * <p>Refusals, all in plain words: the hit id does not exist (NOT_FOUND);
 * the row lives in another space than declared — filing across the space
 * segment would corrupt it; the row carries no response plane — nothing was
 * recorded to promote, inventing a body would be dishonest. A second click
 * on the same row skips idempotently when the space already serves that
 * method+path (import-channel coverage, no twin rule).
 */
    MockStubImportResult promote(long spaceId, long hitId);
}

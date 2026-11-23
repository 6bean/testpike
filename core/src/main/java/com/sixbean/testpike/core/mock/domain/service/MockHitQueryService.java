package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockHitPageView;

import java.time.LocalDateTime;

/**
 * Hit ledger read side — the query door of the request
 * inspector. The write door is {@link MockHitLedgerService}; keeping them apart
 * means the landing path (MQ consumer, latency-critical) never gets tangled
 * with paging/filtering SQL, while both still touch exactly one table.
 */
public interface MockHitQueryService {

    /**
     * One inspector page for a space, newest serve-time first.
     * <p>matched=null asks for both spectra (hits and misses interleaved on the
     * timeline), true for hits only, false for misses only (the "why is my
     * mock not hitting" view). An unknown spaceId is refused with NOT_FOUND —
     * answering an empty page for a ghost id would pretend the ledger is fine.
     */
    MockHitPageView listBySpace(long spaceId, Boolean matched, int page, int pageSize);

    /**
 * Sorting form : forwards a whitelisted sort key and
 * direction so the ORDER BY happens in core SQL over the physical columns
 * and masked jsonb facets the inspector renders. Null/blank {@code sortBy}
 * keeps the historical newest-first timeline; an off-list key is refused
 * fail-closed in plain words (only whitelist values ever reach SQL).
 */
    MockHitPageView listBySpace(long spaceId, Boolean matched, int page, int pageSize,
                                 String sortBy, String sortDir);

    /**
     * Ledger row count for a space, optionally from a {@code since} instant on
     * (the CI verdict question "did my run actually hit the mock"). matched
     * keeps the same spectrum as the page query. A ghost space is refused,
     * never answered with a friendly zero that would pass a verdict on a
     * typo'd space id.
     */
    long countBySpace(long spaceId, LocalDateTime since, Boolean matched);
}

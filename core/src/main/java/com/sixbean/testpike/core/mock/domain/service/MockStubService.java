package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubPageView;

import java.util.List;

/**
 * Mock stub atomic service — the write/read face of the single rule source
 * (UI, snapshot loader and executor all consume these rows, never a copy).
 */
public interface MockStubService {

    /** Create a rule under a space (save-chain validation single point). */
    long create(long spaceId, MockStubInput input);

    /** Read one rule by id (404 in plain words when absent). */
    MockStubInfo get(long id);

    /** Rules of one space in evaluation order (priority asc, id asc). */
    List<MockStubInfo> listBySpace(long spaceId);

    /**
 * One paged window of the space's rules, evaluation order kept per page
 * .
 */
    MockStubPageView listBySpacePaged(long spaceId, int page, int pageSize);

    /**
     * Edit one rule in place through the same save-chain gates as create
     * (validation single point; provenance columns and the owning space are
     * immutable). The caller's echoed row version guards against lost
     * updates: a stale echo comes back as a plain-word version conflict.
     * Returns the refreshed row so callers never re-read blindly.
     */
    MockStubInfo update(long stubId, MockStubInput input);

    /**
     * Remove one rule. The owning space's content pin moves with the row, so
     * the traffic plane stops serving the endpoint on the next poll; hit
     * ledger rows survive (traffic history is never edited away).
     */
    void delete(long stubId);

    /**
     * Matched-serve count of one rule over the last {@code days} days — the
     * impact preview the confirmation door shows before a delete/edit of a
     * live rule (ghost rule refused in plain words).
     */
    long recentHitCount(long stubId, int days);
}

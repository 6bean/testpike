package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;

import java.util.List;

/**
 * Mock space atomic service (core service layer; admin reaches it through the
 * internal bridge, mock-server through the snapshot endpoint).
 */
public interface MockSpaceService {

    /** Create a space (save-chain gates: key shape + production-env ban). */
    long create(long projectId, MockSpaceInput input);

    /**
 * Create a space with an optional expiry (CI throwaway doors, .
 * ttlHours = the space self-retires in the scheduled sweep after N hours
 * (1..8760); null = never expires, the posture of every UI-created space.
 * Same save-chain gates as {@link #create} — one implementation, no second copy.
 */
    long createWithTtl(long projectId, MockSpaceInput input, Integer ttlHours);

    /** Read one space by id (404 in plain words when absent). */
    MockSpaceInfo get(long id);

    /** Spaces of one project, routing order (priority asc, id asc). */
    List<MockSpaceInfo> listByProject(long projectId);

    /**
     * Version-pinned rule snapshot for the mock-server loader, addressed by
     * the global routing handle. Only a PUBLISHED space serves rules — draft
     * and retired snapshots honestly return an empty stub list (the loader
     * swaps to the empty index the moment the pin moves; last-good pin
     * continuation belongs exclusively to a published space with a broken
     * upstream link, never to a non-published one). Disabled stubs are
     * excluded; the version moves only on real drift.
     */
    MockRuleSnapshotView snapshot(String spaceKey);

    /**
     * Three-state lifecycle transition (draft 0 → published 1 → retired 2).
     * The legal edge set is exactly those two edges: unpublish and revive are
     * refused (retired is terminal — copy the space instead), the optimistic
     * lock on expectedVersion makes a racing loser fail with a version
     * conflict, and publishing re-runs the production-environment gate so a
     * draft made before its env turned production can never go live.
     * Returns the refreshed space row so callers never re-read blindly.
     */
    MockSpaceInfo transition(long spaceId, int targetStatus, int expectedVersion);

    /**
 * Move the space's miss ladder : passthrough | reject |
 * llm-fallback, guarded by the same optimistic lock the transition door
 * uses. The pin moves with it — the traffic plane re-pins on the drift.
 */
    MockSpaceInfo updateMissPolicy(long spaceId, String missPolicy, int expectedVersion);

    /**
 * Expired spaces still carrying lifecycle work (scheduled sweep entry,
 * : {@code expires_at} in the past and status draft/published
 * (retired rows are settled history). Routing order, plain read — the
 * sweep decides disposition per row through the shared reference gate.
 */
    List<MockSpaceInfo> listExpiredForSweep();

    /**
 * Hard delete of a never-published draft :
 * retiring a draft would land it in a state its own refusal words say it
 * can never legally reach, so expiry settles drafts by deletion. Refuses
 * anything but a draft, re-checks the reference gate (one single source),
 * removes the four child tables (tp_mock_stub / scenario / recording /
 * ai_job — FK NO ACTION, child-first order) and leaves an audit row.
 * The hit ledger intentionally survives as traffic accounting history.
 */
    void deleteDraft(long spaceId);
}

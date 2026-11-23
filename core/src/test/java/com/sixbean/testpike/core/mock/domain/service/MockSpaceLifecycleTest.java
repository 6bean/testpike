package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock space three-state lifecycle gate .
 * <p>The legal edge set is exactly {draft→published, published→retired}:
 * an unpublish shortcut is refused because a published space already served
 * traffic, and retired is terminal — reviving history would silently re-open
 * an endpoint operators put out of service. Copy the space instead.
 * <p>Snapshot honesty is the other half: the traffic plane only ever sees
 * stubs while the space is published. Draft and retired snapshots keep the
 * space header and the generatedAt stamp but carry an empty rule list, so a
 * mock-server that pinned this version stops serving without any special
 * error path (the last-good pin continuation belongs ONLY to a published
 * space whose upstream link broke, never to a non-published space).
 * <p>Transitions are optimistic-locked on the space row version (a racing
 * loser gets 40901, not a double audit), and publishing re-runs the
 * production-environment gate — an env flipped to prod after draft creation
 * can never be published over (fail-closed).
 * <p>Real dev database, zero mocks (same posture as MockRuleServiceTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockSpaceLifecycleTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t359b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newEnv(long projectId, int envType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, config_json)"
                        + " VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + envType + "-" + UUID.randomUUID().toString().substring(0, 8),
                envType);
    }

    private MockSpaceInput spaceInput(String key, Long envId) {
        return new MockSpaceInput("Space " + key, key, null, envId,
                "prefix", "none", null, null, 100, null);
    }

    private MockSpaceInput spaceInput(String key, Long envId, Integer chaosPercent) {
        return new MockSpaceInput("Space " + key, key, null, envId,
                "prefix", "none", null, null, 100, chaosPercent);
    }

    private long newDraftSpace(String keyPrefix) {
        String key = keyPrefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(newProject(), spaceInput(key, null));
    }

    @Test
    void publishThenRetireMovesStatusAndBumpsOptimisticVersion() {
        long spaceId = newDraftSpace("pub");
        spaceService.transition(spaceId, 1, 0);
        assertEquals(1, spaceService.get(spaceId).status().intValue(), "draft publishes to 1");
        assertEquals(1, spaceService.get(spaceId).version().intValue(), "each transition bumps the lock version");

        spaceService.transition(spaceId, 2, 1);
        assertEquals(2, spaceService.get(spaceId).status().intValue(), "published retires to 2");
    }

    @Test
    void snapshotServesStubsOnlyWhilePublished() {
        String key = "snap-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(newProject(), spaceInput(key, null));
        stubService.create(spaceId, new com.sixbean.testpike.core.mock.api.dto.MockStubInput(
                "live", 100, "GET", "exact", "/live", "[]", "{}", null, null, null,
                "manual", null, true));

        MockRuleSnapshotView draft = spaceService.snapshot(key);
        assertTrue(draft.stubs().isEmpty(), "a draft space must not serve any rule to the traffic plane");
        assertEquals(key, draft.space().spaceKey(), "draft snapshot keeps the space header honestly");

        spaceService.transition(spaceId, 1, 0);
        MockRuleSnapshotView published = spaceService.snapshot(key);
        assertEquals(1, published.stubs().size(), "published snapshot carries the enabled rules");
        assertNotEquals(draft.version(), published.version(), "the status change alone must move the pin");

        spaceService.transition(spaceId, 2, 1);
        MockRuleSnapshotView retired = spaceService.snapshot(key);
        assertTrue(retired.stubs().isEmpty(), "retiring stops serving immediately — no last-good continuation");
        assertNotEquals(published.version(), retired.version(), "retire must move the pin so loaders swap to the empty index");

        // pin semantics ( checklist): with no rules on serve the pin
        // must still drift on any real edit — the space row's version column is
        // the second salt so updated_at alone being equal never pins a stale view.
        long emptySpaceId = newDraftSpace("pinsalt");
        String emptyKey = spaceService.get(emptySpaceId).spaceKey();
        String v1 = spaceService.snapshot(emptyKey).version();
        spaceService.transition(emptySpaceId, 1, 0);
        assertNotEquals(v1, spaceService.snapshot(emptyKey).version(),
                "an empty space's pin moves on publish via the version/status columns (updated_at ties cannot fake it)");
    }

    @Test
    void unknownSpaceKeySnapshotStillFailsHonest() {
        assertThrows(ServiceException.class,
                () -> spaceService.snapshot("no-such-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    @Test
    void unpublishAndDraftSkipAndReviveAreAllRefused() {
        long spaceId = newDraftSpace("edge");
        // draft → retired: skipping publish would retire something that never served.
        assertThrows(ServiceException.class, () -> spaceService.transition(spaceId, 2, 0));
        assertEquals(0, spaceService.get(spaceId).status().intValue(), "refused edge must not land");

        spaceService.transition(spaceId, 1, 0);
        // published → draft (unpublish) is not in the legal edge set.
        ServiceException unpublish = assertThrows(ServiceException.class,
                () -> spaceService.transition(spaceId, 0, 1));
        assertTrue(unpublish.getMessage().contains("发布"), "refusal must speak plain words about the edge");

        spaceService.transition(spaceId, 2, 1);
        // retired is terminal — no revive in any direction.
        assertThrows(ServiceException.class, () -> spaceService.transition(spaceId, 1, 2));
        assertThrows(ServiceException.class, () -> spaceService.transition(spaceId, 0, 2));
        assertEquals(2, spaceService.get(spaceId).status().intValue());

        // self-transitions are outside the edge set too (idempotent re-publish would double-audit).
        long other = newDraftSpace("self");
        assertThrows(ServiceException.class, () -> spaceService.transition(other, 0, 0));
    }

    @Test
    void staleExpectedVersionLosesTheRaceWithVersionConflict() {
        long spaceId = newDraftSpace("race");
        // Two callers both read the draft at version 0; the loser arrives after
        // the winner landed. The edge itself is still legal (draft→publish) —
        // only the optimistic lock can stop it, and it must (40901, not a
        // silent double-publish/double-audit).
        ServiceException loser = assertThrows(ServiceException.class,
                () -> spaceService.transition(spaceId, 1, 7));
        assertEquals(BizErrorCode.VERSION_CONFLICT, loser.getCode());

        spaceService.transition(spaceId, 1, 0);
        assertEquals(1, spaceService.get(spaceId).status().intValue());
        assertEquals(1, spaceService.get(spaceId).version().intValue(),
                "the winner's transition is the only one that landed");
    }

    @Test
    void publishRechecksTheProductionEnvGateFailClosed() {
        long projectId = newProject();
        long envId = newEnv(projectId, 2);
        String key = "pg-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(projectId, spaceInput(key, envId));

        // the env turns production after the draft landed — the save chain could not see the future
        jdbcTemplate.update("UPDATE tp_project_env SET env_type = 4 WHERE id = ?", envId);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceService.transition(spaceId, 1, 0));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertEquals(0, spaceService.get(spaceId).status().intValue(),
                "a refused publish must leave the space in draft (transition rolls back atomically)");
    }

    @Test
    void retiringASpaceWithOpenRecordingIsRefusedWithConflict() {
        // the reference gate guards the manual edge too (one
        // single source shared with the expiry sweep) — a space whose cassette
        // box is still open cannot be retired out from under a running recording.
        String key = "refg-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(newProject(), spaceInput(key, null));
        spaceService.transition(spaceId, 1, 0);
        jdbcTemplate.update("""
                INSERT INTO tp_mock_recording (space_id, target_base_url, status, started_at, updated_at)
                VALUES (?, 'http://127.0.0.1:1/', 'recording', now(), now())
                """, spaceId);

        ServiceException blocked = assertThrows(ServiceException.class,
                () -> spaceService.transition(spaceId, 2, 1));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, blocked.getCode(),
                "blocking references answer 40904, not a generic validation error");
        assertEquals(1, spaceService.get(spaceId).status().intValue(),
                "a refused retire must leave the space published");

        // Sealing the box (stopped) clears the reference — the edge then lands.
        jdbcTemplate.update("UPDATE tp_mock_recording SET status = 'stopped' WHERE space_id = ?", spaceId);
        spaceService.transition(spaceId, 2, 1);
        assertEquals(2, spaceService.get(spaceId).status().intValue());
    }

    @Test
    void chaosDialPersistsOnTheSpaceRow() {
        // the space-level chaos dial is save-chain content — the
        // created space must read back exactly what was filed, and omission
        // defaults to the honest zero (no injection).
        String key = "chaos-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(newProject(), spaceInput(key, null, 25));
        assertEquals(25, spaceService.get(spaceId).chaosPercent().intValue(),
                "the filed dial value must read back off the row");

        String plainKey = "chaos0-" + UUID.randomUUID().toString().substring(0, 10);
        long plainId = spaceService.create(newProject(), spaceInput(plainKey, null));
        assertEquals(0, spaceService.get(plainId).chaosPercent().intValue(),
                "omitted dial defaults to zero injection");
    }

    @Test
    void chaosDialMovesTheSnapshotPin() {
        // The dial is traffic content: a snapshot pinned under one percentage
        // must swap when the dial drifts, so the loader can never serve a
        // generation the pin was not taken under (same posture as proxy target).
        String lowKey = "chaospin-" + UUID.randomUUID().toString().substring(0, 10);
        long lowId = spaceService.create(newProject(), spaceInput(lowKey, null, 0));
        spaceService.transition(lowId, 1, 0);
        String lowPin = spaceService.snapshot(lowKey).version();

        String highKey = "chaospin-" + UUID.randomUUID().toString().substring(0, 10);
        long highId = spaceService.create(newProject(), spaceInput(highKey, null, 80));
        spaceService.transition(highId, 1, 0);
        assertNotEquals(lowPin, spaceService.snapshot(highKey).version(),
                "a different chaos dial must move the pin — same stubs, different served behaviour");
    }

    @Test
    void outOfRangeChaosDialIsRefusedWithPlainWords() {
        // Save-chain gate: 101 has no honest meaning as a percentage and a
        // negative dial would be a silent config typo — refuse loudly before
        // any row lands (the DB CHECK is only the last side-door word).
        String key = "chaosbad-" + UUID.randomUUID().toString().substring(0, 10);
        ServiceException high = assertThrows(ServiceException.class,
                () -> spaceService.create(newProject(), spaceInput(key, null, 101)));
        assertTrue(high.getMessage().contains("0-100"), "refusal must name the legal range");
        ServiceException low = assertThrows(ServiceException.class,
                () -> spaceService.create(newProject(), spaceInput(key, null, -1)));
        assertTrue(low.getMessage().contains("0-100"), "negative dial refused with the same words");
    }

    @Test
    void createWithTtlStampsHonestExpiryAndOmittedTtlMeansNever() {
        // CI throwaway spaces : a TTL set at creation writes an
        // expiry instant the sweep job will later honour; no TTL = the column
        // stays NULL, which must mean "never expires", not "unknown".
        long projectId = newProject();
        String key = "ttl-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.createWithTtl(projectId, spaceInput(key, null), 2);
        var info = spaceService.get(spaceId);
        assertNotNull(info.expiresAt(), "a 2h TTL must land as a concrete expiry instant");
        java.time.LocalDateTime stamp = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM tp_mock_space WHERE id = ?",
                java.time.LocalDateTime.class, spaceId);
        assertEquals(info.expiresAt(), stamp, "view and row speak the same expiry");
        assertTrue(stumpish(info.expiresAt()), "expiry sits roughly two hours ahead, not minutes");

        String open = "ttl0-" + UUID.randomUUID().toString().substring(0, 10);
        assertNull(spaceService.get(spaceService.createWithTtl(projectId, spaceInput(open, null), null))
                .expiresAt(), "no TTL = never expires");
    }

    @Test
    void absurdTtlIsRefusedWithPlainWords() {
        // 0 hours would expire the space the instant it is created and a
        // negative one is a silent typo; the ceiling keeps CI leftovers from
        // squatting in the routing plane under a fake "short-lived" label.
        String key = "tt1bad-" + UUID.randomUUID().toString().substring(0, 10);
        ServiceException zero = assertThrows(ServiceException.class,
                () -> spaceService.createWithTtl(newProject(), spaceInput(key, null), 0));
        assertTrue(zero.getMessage().contains("TTL"), "refusal names the TTL it rejected");
        ServiceException huge = assertThrows(ServiceException.class,
                () -> spaceService.createWithTtl(newProject(), spaceInput(key, null), 9999));
        assertTrue(huge.getMessage().contains("TTL"));
    }

    /** now + 2h within a five-minute tolerance (clock jitter never flake). */
    private boolean stumpish(java.time.LocalDateTime expiresAt) {
        var minutes = java.time.Duration.between(
                java.time.LocalDateTime.now().plusHours(2), expiresAt).toMinutes();
        return minutes > -5 && minutes < 5;
    }

    @Test
    void transitionAuditsTheSpaceChange() {
        long spaceId = newDraftSpace("aud");
        spaceService.transition(spaceId, 1, 0);
        Integer counted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_audit_log WHERE object_type = 'mock_space' AND action = 'mock_space_transition'"
                        + " AND object_id = ?",
                Integer.class, String.valueOf(spaceId));
        assertTrue(counted != null && counted >= 1, "every lifecycle edge must leave an audit trail");
    }
}

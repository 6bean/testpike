package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.domain.component.MockSpaceExpirySweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expiry sweep for throwaway mock spaces .
 * <p>A CI-created space carries an optional TTL; once {@code expires_at} is in
 * the past the scheduled sweep decides per lifecycle state:
 * <ul>
 * <li>published + no blocking references → retire through the single-edge
 * state machine (version bump included);</li>
 * <li>published + open recording box → defer: status untouched, one
 * MOCK_SPACE_EXPIRY_DEFERRED notification per project owner (day-window
 * dedup) plus an audit row naming the reasons;</li>
 * <li>draft (never served traffic) + no references → hard delete with its
 * four child tables (retiring a draft that was never published would be
 * semantically wrong — describeRefusal says so for the manual path);</li>
 * <li>not expired → never touched.</li>
 * </ul>
 * Real dev database, zero mocks (same posture as MockSpaceLifecycleTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class MockSpaceExpirySweeperTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private MockSpaceExpirySweeper sweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProjectWithOwner() {
        String tag = "t363c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
        long userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 2)",
                projectId, userId);
        return projectId;
    }

    private long draftSpace(long projectId, String prefix) {
        String key = prefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId, new MockSpaceInput("Space " + key, key, null, null,
                "prefix", "none", null, null, 100, null));
    }

    private void expireNow(long spaceId) {
        jdbcTemplate.update("UPDATE tp_mock_space SET expires_at = now() - interval '1 hour' WHERE id = ?",
                spaceId);
    }

    private void openRecording(long spaceId) {
        jdbcTemplate.update("""
                INSERT INTO tp_mock_recording (space_id, target_base_url, status, started_at, updated_at)
                VALUES (?, 'http://127.0.0.1:1/', 'recording', now(), now())
                """, spaceId);
    }

    @Test
    void expiredPublishedSpaceWithoutReferencesIsRetiredWithVersionBump() {
        long projectId = newProjectWithOwner();
        long spaceId = draftSpace(projectId, "sw-ret");
        spaceService.transition(spaceId, 1, 0);
        expireNow(spaceId);

        int swept = sweeper.sweepExpiredSpaces();

        assertTrue(swept >= 1, "the sweep must report at least the space it just retired");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_mock_space WHERE id = ?", Integer.class, spaceId).intValue(),
                "an expired published space with nothing holding it open retires to 2");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT version FROM tp_mock_space WHERE id = ?", Integer.class, spaceId).intValue(),
                "the retire goes through the optimistic-lock edge, so the version bumps 0→1→2 path");
    }

    @Test
    void expiredPublishedSpaceWithOpenRecordingIsDeferredWithNotificationAndAudit() {
        long projectId = newProjectWithOwner();
        long spaceId = draftSpace(projectId, "sw-def");
        spaceService.transition(spaceId, 1, 0);
        openRecording(spaceId);
        expireNow(spaceId);

        sweeper.sweepExpiredSpaces();

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_mock_space WHERE id = ?", Integer.class, spaceId).intValue(),
                "an open recording box blocks retirement — the space keeps serving");
        long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM tp_user_project WHERE project_id = ? AND role = 2",
                Long.class, projectId);
        Integer notes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'MOCK_SPACE_EXPIRY_DEFERRED'"
                        + " AND user_id = ? AND project_id = ?",
                Integer.class, ownerId, projectId);
        assertEquals(1, notes.intValue(), "the owner gets exactly one plain-word deferral notice");
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'mock_space_expiry_deferred'"
                        + " AND object_id = ?",
                Integer.class, String.valueOf(spaceId));
        assertEquals(1, audits.intValue(), "the deferral leaves an audit trail naming the reasons");

        // Day-window dedup: a second sweep the same day must not spam the owner.
        sweeper.sweepExpiredSpaces();
        Integer notesAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'MOCK_SPACE_EXPIRY_DEFERRED'"
                        + " AND user_id = ? AND project_id = ?",
                Integer.class, ownerId, projectId);
        assertEquals(1, notesAfter.intValue(), "same-day re-sweep merges into the existing notice");
    }

    @Test
    void expiredDraftWithoutReferencesIsDeletedWithItsChildren() {
        long projectId = newProjectWithOwner();
        long spaceId = draftSpace(projectId, "sw-del");
        stubService.create(spaceId, new MockStubInput("rule", 100, "GET", "exact", "/x", "[]", "{}",
                null, null, null, "manual", null, true));
        expireNow(spaceId);

        sweeper.sweepExpiredSpaces();

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mock_space WHERE id = ?", Integer.class, spaceId);
        assertEquals(0, rows.intValue(), "an expired never-published draft disappears (retire would be"
                + " a state it never legally entered)");
        List<String> children = List.of("tp_mock_stub", "tp_mock_scenario", "tp_mock_recording", "tp_mock_ai_job");
        for (String table : children) {
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE space_id = ?", Integer.class, spaceId).intValue(),
                    table + " must not keep orphan rows under a deleted space");
        }
    }

    @Test
    void unexpiredSpaceIsNeverTouched() {
        long projectId = newProjectWithOwner();
        long spaceId = draftSpace(projectId, "sw-keep");

        sweeper.sweepExpiredSpaces();

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_mock_space WHERE id = ?", Integer.class, spaceId).intValue(),
                "a draft without an expiry stamp stays a draft");
    }
}

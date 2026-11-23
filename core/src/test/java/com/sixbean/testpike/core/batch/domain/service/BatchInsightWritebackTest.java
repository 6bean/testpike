package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightUpsertInput;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the batch failure-insight write-back (AO-7 proactive insights, stage 2).
 * <p>Design pins (recon card , stage-2 split):
 * ① the clustering consumer lives in ai-service but NEVER writes business tables directly —
 * results land through this core internal service (two-sides separation, mock precedent);
 * ② one insight row per batch, replay overwrites the latest cluster snapshot
 * (ON CONFLICT (batch_id) DO UPDATE — same latest-wins semantics as the classify write-back);
 * ③ landing an insight fans out an AI_INSIGHT_CLUSTER station letter to the batch's project,
 * recipients = tp_project.test_owner_id with the ProjectOwners fallback family
 * (role=2 owners ∪ creator), dedup key AI_INSIGHT_CLUSTER:{batchId}:{userId};
 * ④ unknown batch = ServiceException (single-point validation in core, ai-service must not
 * be able to forge a row for a batch that does not exist);
 * ⑤ project id on the stored row is read back from the batch row (server-derived, never
 * trusted from the payload — H1 isolation anchor).
 * <p>Real dev database, nothing mocked (strict-testing-standards).
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchInsightWritebackTest {

    @Autowired
    private BatchInsightService batchInsightService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline): projects live in the
    // name family below (codes INS*), the FK child-first sweep covers
    // tp_user_project under tp_project plus the project-keyed defaults
    // (tp_exec_batch, tp_notification), and tp_batch_insight — project-keyed with
    // no FK into tp_exec_batch — rides extraProjectTables. This suite's recipients
    // keep the fixed 'insight-owner' username family, so they purge by pattern
    // AFTER the project sweep (their notifications and audit rows go first).
    // The old blanket TRUNCATE wiped every shipped tp_user row; the sweep does not.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "洞察项目INS";
    private static final String FIXTURE_USER_NAME_PATTERN = "insight-owner%";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of("tp_batch_insight"),
                List.of(FIXTURE_PROJECT_NAME_PREFIX));
        com.sixbean.testpike.core.support.SharedDbFixtureSweep
                .usersByPatternDown(jdbcTemplate, FIXTURE_USER_NAME_PATTERN);
    }

    private long createProject(String code, Long testOwnerId) {
        Long id = projectService.create(1L, new ProjectCreateInput("洞察项目" + code, code, "zh-CN", "P1", null));
        assert id != null;
        if (testOwnerId != null) {
            jdbcTemplate.update("UPDATE tp_project SET test_owner_id = ? WHERE id = ?", testOwnerId, id);
        }
        return id;
    }

    private long createUser(String username) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, display_name, password_hash, status) "
                        + "VALUES (?, ?, 'x', 1) RETURNING id",
                Long.class, username, username);
        assert id != null;
        return id;
    }

    private long createBatch(long projectId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) "
                        + "VALUES (?, ?, '洞察批次', 7, 1) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime());
        assert id != null;
        return id;
    }

    private BatchInsightUpsertInput sampleInput(int unattributed) {
        return new BatchInsightUpsertInput(
                List.of(Map.of("class", "PRODUCT_DEFECT", "label", "产品缺陷", "count", 2),
                        Map.of("class", "ENV", "label", "环境问题", "count", 1)),
                unattributed, 3, "共 3 条失败，聚成 2 类：产品缺陷×2、环境问题×1");
    }

    @Test
    void landingInsightStoresRowWithServerDerivedProjectId() {
        long owner = createUser("insight-owner");
        long projectId = createProject("INS1", owner);
        long batchId = createBatch(projectId);

        batchInsightService.upsert(batchId, sampleInput(0));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tp_batch_insight WHERE batch_id = ?", batchId);
        assertEquals(1, rows.size());
        // pin ⑤: project id comes from the batch row, not from the payload
        assertEquals(projectId, ((Number) rows.get(0).get("project_id")).longValue());
        assertEquals(3, ((Number) rows.get(0).get("fail_count")).intValue());
        assertTrue(String.valueOf(rows.get(0).get("summary")).contains("产品缺陷"));
    }

    @Test
    void replayOverwritesLatestSnapshotKeepingSingleRow() {
        long owner = createUser("insight-owner2");
        long projectId = createProject("INS2", owner);
        long batchId = createBatch(projectId);

        batchInsightService.upsert(batchId, sampleInput(0));
        batchInsightService.upsert(batchId, sampleInput(2));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tp_batch_insight WHERE batch_id = ?", batchId);
        assertEquals(1, rows.size(), "replay must not double the ledger");
        assertEquals(2, ((Number) rows.get(0).get("unattributed_count")).intValue(),
                "latest replay wins");
    }

    @Test
    void landingFansOutInsightNotificationToTestOwner() {
        long owner = createUser("insight-owner3");
        long projectId = createProject("INS3", owner);
        long batchId = createBatch(projectId);

        batchInsightService.upsert(batchId, sampleInput(0));

        List<Map<String, Object>> notes = jdbcTemplate.queryForList(
                "SELECT * FROM tp_notification WHERE event_type = 'AI_INSIGHT_CLUSTER' "
                        + "AND project_id = ?", projectId);
        assertEquals(1, notes.size(), "fan-out counted inside this fixture project only");
        assertEquals(owner, ((Number) notes.get(0).get("user_id")).longValue());
        assertEquals("AI_INSIGHT_CLUSTER:" + batchId + ":" + owner,
                notes.get(0).get("dedup_key"));
        // honest attribution note (recon pin: unattributed count must be visible)
        assertTrue(String.valueOf(notes.get(0).get("content")).contains("批次"));
    }

    @Test
    void notificationSkippedWhenNoRecipientsResolve() {
        long projectId = createProject("INS4", null);
        // projectService.create auto-mints a role=2 owner row for the creator
        // (01 semantics) — empty the whole recipient family to reach the
        // no-recipient branch: no test owner, no role=2 row, no creator.
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("UPDATE tp_project SET created_by = NULL WHERE id = ?", projectId);
        long batchId = createBatch(projectId);

        batchInsightService.upsert(batchId, sampleInput(1));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_insight WHERE batch_id = ?", Long.class, batchId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'AI_INSIGHT_CLUSTER' "
                        + "AND project_id = ?",
                Long.class, projectId).intValue());
    }

    @Test
    void unknownBatchRejectedAtSingleValidationPoint() {
        assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                () -> batchInsightService.upsert(999999999L, sampleInput(0)));
    }

    @Test
    void insightEventIsRegisteredInNotificationDict() {
        assertTrue(NotificationEventDict.isKnown("AI_INSIGHT_CLUSTER"));
        assertFalse(NotificationEventDict.isForced("AI_INSIGHT_CLUSTER"));
        assertTrue(NotificationEventDict.isDefaultEnabled("AI_INSIGHT_CLUSTER"),
                "AI-driven reminders are on by default (factory-default-on ruling)");
    }
}

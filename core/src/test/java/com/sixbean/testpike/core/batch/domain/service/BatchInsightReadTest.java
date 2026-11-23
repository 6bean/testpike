package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightUpsertInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightView;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the batch failure-insight read door (AO-7 stage 3 read path).
 * <p>Pins:
 * ① a landed insight reads back as a view with counts, parsed cluster groups and
 *   the plain-word summary — the report-page card consumes this shape as stored,
 *   zero recomputation on the read path;
 * ② a batch without a landed insight answers null (honest absence — the frontend
 *   hides the card, no 404 error toast for the normal all-pass/gate-off case);
 * ③ unknown batch id also answers null (never forges a row, never throws).
 * <p>Real dev database, nothing mocked (strict-testing-standards).
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchInsightReadTest {

    @Autowired
    private BatchInsightService batchInsightService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline): projects live in the
    // name family below; the FK child-first sweep covers tp_user_project under
    // tp_project plus the project-keyed defaults (tp_exec_batch, tp_notification).
    // tp_batch_insight carries project_id with no FK into tp_exec_batch, so it
    // rides extraProjectTables. tp_user holds shipped rows the old CASCADE wiped —
    // this suite creates no users, the sweep leaves the table alone.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "洞察读项目IRR";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of("tp_batch_insight"),
                List.of(FIXTURE_PROJECT_NAME_PREFIX));
    }

    private long createProject(String code) {
        Long id = projectService.create(1L, new ProjectCreateInput("洞察读项目" + code, code, "zh-CN", "P1", null));
        assert id != null;
        return id;
    }

    private long createBatch(long projectId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) "
                        + "VALUES (?, ?, '洞察读批次', 7, 1) RETURNING id",
                Long.class, projectId, "BR-" + System.nanoTime());
        assert id != null;
        return id;
    }

    @Test
    void landedInsightReadsBackWithParsedClustersAndCounts() {
        long projectId = createProject("IRR1");
        long batchId = createBatch(projectId);
        batchInsightService.upsert(batchId, new BatchInsightUpsertInput(
                List.of(Map.of("class", "PRODUCT_DEFECT", "label", "产品缺陷", "count", 2),
                        Map.of("class", "ENV", "label", "环境问题", "count", 1)),
                1, 3, "共 3 条失败，聚成 2 类"));

        BatchInsightView view = batchInsightService.findByBatch(batchId);

        assertNotNull(view, "a landed insight must read back");
        assertEquals(batchId, view.batchId());
        assertEquals(3, view.failCount());
        assertEquals(1, view.unattributedCount());
        assertTrue(view.summary().contains("聚成 2 类"));
        List<Map<String, Object>> clusters = view.clusters();
        assertEquals(2, clusters.size(), "cluster groups must be parsed, not raw JSON text");
        assertEquals("PRODUCT_DEFECT", clusters.get(0).get("class"));
        assertEquals(2, ((Number) clusters.get(0).get("count")).intValue());
    }

    @Test
    void batchWithoutInsightAnswersHonestAbsence() {
        long projectId = createProject("IRR2");
        long batchId = createBatch(projectId);

        assertNull(batchInsightService.findByBatch(batchId),
                "no landed insight = null (card hidden on the report page, no error)");
    }

    @Test
    void unknownBatchAnswersHonestAbsenceWithoutThrowing() {
        assertNull(batchInsightService.findByBatch(999_999_999L));
    }
}

package com.sixbean.testpike.core.batch.support;

import com.sixbean.testpike.core.batch.domain.component.BatchFinalizeSupport;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared batch-report test fixture (R40 single source: the three report-count
 * tests kept identical project/case/batch builders, finalize trigger and
 * sweep blocks — one implementation now serves all consumers).
 * <p>Lifecycle contract for consumers: call {@link #isolateSideChannels()} in
 * {@code @BeforeEach} and {@link #sweepFixtureRows()} in {@code @AfterEach}.
 * Rows are always self-built and swept by the tracked project ids — factory
 * tables are never truncated and never depended on (R37).
 * <p>Registered as a Spring component so consumers autowire exactly one
 * collaborator instead of repeating an injection block per class.
 */
@Component
public class BatchReportFixture {

    private final BatchFinalizeSupport batchFinalizeSupport;
    private final CaseService caseService;
    private final ProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    /** Fixture projects created since the last sweep. */
    private final List<Long> fixtureProjectIds = new ArrayList<>();

    public BatchReportFixture(BatchFinalizeSupport batchFinalizeSupport,
                              CaseService caseService,
                              ProjectService projectService,
                              JdbcTemplate jdbcTemplate) {
        this.batchFinalizeSupport = batchFinalizeSupport;
        this.caseService = caseService;
        this.projectService = projectService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Turn off auto-defect creation and the Feishu push side channel for the
     * duration of the test: report tests assert the snapshot/in-site contract
     * only, and the dual-send contract is covered elsewhere.
     */
    public void isolateSideChannels() {
        upsertConfig("defect.auto-create.enabled", "0");
        upsertConfig("notify.feishu.enabled", "0");
    }

    /**
     * R37 teardown: delete only this fixture's projects and their descendants.
     * tp_batch_report/tp_test_case carry project_id without an FK into
     * tp_project, so they are swept as extra project-keyed tables.
     */
    public void sweepFixtureRows() {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                List.of("tp_batch_report", "tp_test_case"),
                fixtureProjectIds.stream().mapToLong(Long::longValue).toArray());
        fixtureProjectIds.clear();
    }

    /**
     * Unique code per run: fixtures never depend on factory rows and never
     * collide with a crashed run's leftovers (the sweep removes these projects).
     * The operator's auto-binding (member id=1) is dropped so the recipient
     * fallback cannot pick a ghost owner and break the isolation assertions.
     */
    public long createProject(String code) {
        String uniqueCode = code + "-" + System.nanoTime() % 1_000_000_000L;
        Long id = projectService.create(1L, new ProjectCreateInput("proj" + uniqueCode, uniqueCode, "zh-CN", "P1", null));
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ? AND user_id = 1", id);
        fixtureProjectIds.add(id);
        return id;
    }

    public long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "pre", "exp",
                1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    /**
     * Finished batch with tasks; each entry = [caseId, status]. started_at is
     * pushed 10s back so finalize sees a plausible duration.
     */
    public long finalizedBatch(long projectId, String batchName, Object[][] tasks) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 4, 1, now() - interval '10 seconds', now()) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime(), batchName);
        assert batchId != null;
        for (Object[] t : tasks) {
            jdbcTemplate.update(
                    "INSERT INTO tp_exec_task (project_id, case_id, batch_id, status) VALUES (?, ?, ?, ?)",
                    projectId, t[0], batchId, t[1]);
        }
        return batchId;
    }

    public void setResultExt(long batchId, int ordinal, String json) {
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE batch_id = ? ORDER BY id LIMIT 1 OFFSET ?",
                Long.class, batchId, ordinal);
        jdbcTemplate.update("UPDATE tp_exec_task SET result_ext = ?::jsonb WHERE id = ?", json, taskId);
    }

    /** Single finalize trigger shared with every consumer (red line 6: no second finalize chain). */
    public void finalizeBatch(long batchId) {
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));
    }

    /** Snapshot column of the report row (read-path asserts use the stored value verbatim). */
    public int snapshotColumn(long batchId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM tp_batch_report WHERE batch_id = ?",
                Integer.class, batchId);
    }

    public Map<String, Object> reportRow(long batchId) {
        return jdbcTemplate.queryForMap("SELECT * FROM tp_batch_report WHERE batch_id = ?", batchId);
    }

    public JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    private void upsertConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES (?, ?, 'system', 'int', 0, 'batch report test fixture')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value);
    }
}

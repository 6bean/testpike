package com.sixbean.testpike.core.session.fixture;

import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Shared fixture steps for the session-domain integration suites (R40 single
 * source): create an approved web case and save the action script that
 * declares a ledger login state. The start-gate suite and the auth-rejection
 * writeback suite each carried a byte-identical private copy; the duplication
 * gate flagged the second consumer, so both now delegate here (red line 6
 * discipline applies to test fixtures too — one copy, zero drift).
 */
public final class CaseSessionFixtures {

    private final CaseService caseService;
    private final ScriptService scriptService;
    private final JdbcTemplate jdbcTemplate;

    public CaseSessionFixtures(CaseService caseService, ScriptService scriptService,
                               JdbcTemplate jdbcTemplate) {
        this.caseService = caseService;
        this.scriptService = scriptService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** A web case moved to status 3 (approved), ready for batch assembly. */
    public long approvedCase(long projectId, String title, String caseNo) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title,
                "前置", "预期", 1, 2, "web", 3, 2, 1, null, List.of()));
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", info.id());
        return info.id();
    }

    /**
     * Removes the snapshot rows a suite family wrote; must run BEFORE
     * purgeProjectFixtures (tp_exec_step_snapshot references tp_exec_task).
     */
    public void purgeSnapshots(long projectId) {
        jdbcTemplate.update("DELETE FROM tp_exec_step_snapshot WHERE task_id IN "
                + "(SELECT t.id FROM tp_exec_task t JOIN tp_exec_batch b ON t.batch_id = b.id"
                + " WHERE b.project_id = ?)", projectId);
    }

    /**
     * Removes every fixture row the session suites can create, in FK order,
     * scoped to the fixture project — the factory baseline is never touched
     * (R37: no TRUNCATE of shipped tables).
     */
    public void purgeProjectFixtures(long projectId) {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_batch_param_snapshot WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id = ?", projectId);
        // Pool-isolation tags (type 7) hang off the fixture project and are
        // referenced by tp_exec_batch.pool_tag_id, so they drop after batches.
        jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** Script whose action JSON declares a sessionState name (claim channel). */
    public void sessionScript(long caseId, String stateName) {
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\":" + caseId + ",\"sessionState\":{\"name\":\"" + stateName
                        + "\"},\"steps\":[]}", 2));
    }
}

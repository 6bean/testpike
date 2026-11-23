package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Shared fixtures for page-element-ledger tests (WE batch S3): project boot,
 * the confirmed-row door sequence and the full teardown cascade (tickets,
 * act tasks, transition events, ledger rows, membership, project) live HERE
 * once — sibling test classes consumed copies, and a fourth copy arriving in
 * a new test tripped the R40 single-source gate, so the pattern was extracted
 * instead of cloned again. Deletes only the rows passed in / owned by the
 * given projects; factory baseline tables stay untouched.
 */
public final class WebElementLedgerFixtures {

    private WebElementLedgerFixtures() {
    }

    /** Register-and-track a throwaway project for the ledger tests. */
    public static long createProject(JdbcTemplate jdbc, ProjectService projectService,
                                     List<Long> trackedIds, String namePrefix) {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbc, projectService, 1L, trackedIds,
                namePrefix + code, code);
    }

    /** Confirmed ledger row: the candidate-door create, then the state door. */
    public static WebElementView confirmed(WebElementService webElementService, long projectId,
                                           String name, String pageUrl,
                                           List<WebElementAnchorInput> anchors) {
        WebElementView created = webElementService.create(
                new WebElementInput(projectId, name, pageUrl, anchors));
        return webElementService.changeState(created.id(), 1, created.revision());
    }

    /** Teardown cascade for one fixture project (children before parents). */
    public static void purge(JdbcTemplate jdbc, long projectId) {
        jdbc.update("DELETE FROM tp_appauto_step_suggestion WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                        + " AND job_ref IN (SELECT id::text FROM tp_web_element WHERE project_id = ?)",
                projectId);
        jdbc.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }
}

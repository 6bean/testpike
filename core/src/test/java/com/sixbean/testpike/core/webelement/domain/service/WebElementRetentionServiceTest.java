package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerRetentionResult;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger retention twin loop : aged candidates retire
 * through the state machine door (audit trail kept), rows already retired
 * age out of the ledger after the retention window with their anchor stats
 * and per-row audit events removed together. Both directions are asserted:
 * expired rows must move, fresh rows must not (a sweep that matched nothing
 * at all here reads as the idle-spin red, R43).
 * <p>Real dev database, no mocks. Keys are the seeded
 * element.candidate-ttl-days / element.retention-days pair (V544).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementRetentionServiceTest {

    private static final int STATE_CANDIDATE = 0;
    private static final int STATE_RETIRED = 3;

    @Autowired
    private WebElementService webElementService;
    @Autowired
    private WebElementRetentionService retentionService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "留存清理项目" + code, code);
    }

    @AfterEach
    void cleanUp() {
        for (Long projectId : fixtureProjectIds) {
            jdbcTemplate.update(
                    "DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                            + " AND job_ref IN (SELECT id::text FROM tp_web_element WHERE project_id = ?)",
                    projectId);
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        fixtureProjectIds.clear();
    }

    private WebElementView create(long projectId, String name, String path) {
        return webElementService.create(new WebElementInput(projectId, name,
                "https://shop.example.com" + path,
                List.of(new WebElementAnchorInput("testid", "btn-" + name, 0.9, null))));
    }

    @Test
    void agedCandidateRetiresThroughTheStateMachineDoorWithAudit() {
        long projectId = createProject();
        WebElementView aged = create(projectId, "过期候选", "/old");
        WebElementView fresh = create(projectId, "新鲜候选", "/fresh");
        pushBackCreated(aged.id(), 120);

        WebElementLedgerRetentionResult result = retentionService.sweep();

        assertEquals(STATE_RETIRED, state(aged.id()),
                "a candidate older than the TTL rides the state door to retired");
        assertEquals(STATE_CANDIDATE, state(fresh.id()),
                "a candidate inside the window must not be touched");
        assertTrue(result.retiredCandidates() >= 1, "the sweep reports the rows it moved (zero-match red, R43)");
        Integer audit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'element-ledger' AND job_ref = ?",
                Integer.class, String.valueOf(aged.id()));
        assertTrue(audit != null && audit >= 1, "the automatic retire keeps the same audit row a human move would");
    }

    @Test
    void agedRetiredRowIsPurgedWithItsStatsAndLedgerEvents() {
        long projectId = createProject();
        WebElementView doomed = create(projectId, "到期退役", "/doomed");
        webElementService.changeState(doomed.id(), STATE_RETIRED, doomed.revision());
        pushBackUpdated(doomed.id(), 120);
        jdbcTemplate.update("INSERT INTO tp_web_element_anchor_stat (element_id, viewport, anchor_type, hits, misses)"
                + " VALUES (?, '1440x900', 'testid', 4, 1)", doomed.id());

        WebElementLedgerRetentionResult result = retentionService.sweep();

        assertEquals(0, countRows(doomed.id()), "row gone after the retention window");
        assertEquals(0, statRows(doomed.id()), "anchor stats leave with the row (delete-set enumeration)");
        assertEquals(0, ledgerEventRows(doomed.id()), "per-row audit events do not orphan the deleted element");
        assertTrue(result.purgedRows() >= 1, "the purge reports what it removed (zero-match red, R43)");
    }

    @Test
    void retiredRowInsideTheWindowIsKept() {
        long projectId = createProject();
        WebElementView kept = create(projectId, "窗内退役", "/kept");
        webElementService.changeState(kept.id(), STATE_RETIRED, kept.revision());
        pushBackUpdated(kept.id(), 5);

        retentionService.sweep();

        assertEquals(STATE_RETIRED, state(kept.id()), "a retired row still inside retention stays readable");
        assertEquals(1, countRows(kept.id()));
    }

    private void pushBackCreated(long id, int days) {
        jdbcTemplate.update("UPDATE tp_web_element SET created_at = now() - make_interval(days => ?) WHERE id = ?",
                days, id);
    }

    private void pushBackUpdated(long id, int days) {
        jdbcTemplate.update("UPDATE tp_web_element SET updated_at = now() - make_interval(days => ?) WHERE id = ?",
                days, id);
    }

    private int state(long id) {
        return jdbcTemplate.queryForObject("SELECT state FROM tp_web_element WHERE id = ?", Integer.class, id);
    }

    private int countRows(long id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM tp_web_element WHERE id = ?", Integer.class, id);
    }

    private int statRows(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element_anchor_stat WHERE element_id = ?", Integer.class, id);
    }

    private int ledgerEventRows(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'element-ledger' AND job_ref = ?",
                Integer.class, String.valueOf(id));
    }
}

package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.WebElementLedgerFixtures;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mismatch re-anchor tickets (WE batch S3, : every ledger entry that
 * exploration reconciliation just moved confirmed→stale gets an element repair
 * suggestion through the SINGLE A9 door — {@code AppautoStepHealingService
 * .record} with the WEB shape (source "dom" = anchor rebuilt from a fresh DOM
 * snapshot, failureClass 2, no carried action, elementId target). No second
 * suggestion surface exists: the ticket lands on the same human-approval board
 * whose adoptWeb chain revives the entry with the approved anchor.
 *
 * <p>Honesty pins: a ticket is only filed when the fresh pool carries a
 * same-named interactive candidate (name ↔ visible text, the same-text strong
 * evidence tier of design A3) — nothing is fabricated out of thin air and rows
 * without evidence are skipped with a count, not an exception. One row's
 * business refusal never blocks its siblings (per-row isolation mirrors the
 * / failure posture: the step journal above already succeeded).
 * Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class ExploreReanchorProposalTest {

    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ExploreReanchorProposal proposal;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        return WebElementLedgerFixtures.createProject(jdbcTemplate, projectService,
                fixtureProjectIds, "重锚建议本项目");
    }

    @AfterEach
    void cleanUp() {
        fixtureProjectIds.forEach(id -> WebElementLedgerFixtures.purge(jdbcTemplate, id));
        fixtureProjectIds.clear();
    }

    /** Confirmed ledger row whose anchors all just missed the fresh pool. */
    private WebElementView confirmed(long projectId, String name, String pageUrl,
                                     List<WebElementAnchorInput> anchors) {
        return WebElementLedgerFixtures.confirmed(webElementService, projectId, name, pageUrl,
                anchors);
    }

    /** Ownership anchor: explore-act task (case_id 0 sentinel) of this project. */
    private long exploreTask(long projectId) {
        return execTaskService.createExploreAct(projectId,
                "{\"kind\":\"explore\",\"action\":\"page.snapshot\","
                        + "\"arguments\":{\"url\":\"http://shop.local/cart\"}}");
    }

    /** Pool where the redesigned page kept the visible text, dropped the old id. */
    private String poolWithRenamedLogin() {
        return """
                {"candidates":[
                   {"tag":"button","text":"登录","target":{"css":"[data-test=\\"login-btn\\"]"}},
                   {"tag":"input","text":"","target":{"css":"[placeholder=\\"用户名\\"]"}}
                 ],"count":2,"truncated":false}""";
    }

    private Map<String, Object> onlyTicket(long projectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tp_appauto_step_suggestion WHERE project_id = ?", projectId);
        assertEquals(1, rows.size(), "exactly one ticket is expected on the board");
        return rows.get(0);
    }

    @Test
    void sameNamedPoolCandidateFilesWebTicketThroughTheRecordDoor() {
        long projectId = createProject();
        long taskId = exploreTask(projectId);
        WebElementView entry = confirmed(projectId, "登录", "http://shop.local/cart",
                List.of(new WebElementAnchorInput("testid", "gone-btn", 0.9, null)));

        ExploreReanchorProposal.Outcome outcome = proposal.file(projectId, 3, taskId,
                List.of(entry.id()), poolWithRenamedLogin());

        assertEquals(1, outcome.filed());
        assertEquals(0, outcome.skipped());
        Map<String, Object> row = onlyTicket(projectId);
        assertEquals("WEB", row.get("object_type"));
        assertEquals(entry.id(), ((Number) row.get("element_id")).longValue());
        assertEquals(taskId, ((Number) row.get("source_task_id")).longValue());
        assertEquals(0, ((Number) row.get("case_id")).intValue(),
                "explore tickets carry the case-0 sentinel like their act task");
        assertEquals(3, ((Number) row.get("step_no")).intValue(),
                "the exploration step sequence rides as step_no");
        assertEquals(2, ((Number) row.get("failure_class")).intValue());
        assertEquals("dom", row.get("source"), "rebuilt from a fresh DOM snapshot");
        assertEquals("testid=gone-btn", row.get("anchor"),
                "the failed anchor is the ticket's signature");
        assertNull(row.get("proposed_action"), "element repair never carries an action");
        assertEquals(1, ((Number) row.get("status")).intValue(), "pending human approval");
        // Stable id/testid css spellings survive as the stronger css proposal.
        String target = (String) row.get("proposed_target");
        assertTrue(target.contains("\"css\"") && target.contains("login-btn"),
                "expected the stable data-test css spelling, got " + target);
        // The adopt door merges this anchor into the ledger, whose write
        // validation rejects a null strength; the ticket must ride the same
        // structural prior the dispatcher already applies to null anchors.
        assertTrue(target.contains("\"strength\":0.6"),
                "css-tier ticket anchor must carry the ledger structural prior 0.6, got " + target);
    }

    @Test
    void fragileCssChainFallsBackToTheVisibleTextAnchor() {
        long projectId = createProject();
        long taskId = exploreTask(projectId);
        WebElementView entry = confirmed(projectId, "提交", "http://shop.local/order",
                List.of(new WebElementAnchorInput("css", "#app > button.go", 0.8, null)));
        String pool = """
                {"candidates":[
                   {"tag":"button","text":"提交","target":{"css":"body>div:nth-child(3)>button"}}
                 ],"count":1,"truncated":false}""";

        ExploreReanchorProposal.Outcome outcome = proposal.file(projectId, 7, taskId,
                List.of(entry.id()), pool);

        assertEquals(1, outcome.filed());
        String target = (String) onlyTicket(projectId).get("proposed_target");
        assertTrue(target.contains("\"text\"") && target.contains("提交"),
                "positional chains are not durable anchors, text should be, got " + target);
        assertTrue(target.contains("\"strength\":0.75"),
                "text-tier ticket anchor must carry the ledger structural prior 0.75, got " + target);
    }

    @Test
    void rowsWithoutSameNamedCandidateAreSkippedHonestlyNotFabricated() {
        long projectId = createProject();
        long taskId = exploreTask(projectId);
        WebElementView entry = confirmed(projectId, "优惠券", "http://shop.local/cart",
                List.of(new WebElementAnchorInput("testid", "coupon", 0.9, null)));

        ExploreReanchorProposal.Outcome outcome = proposal.file(projectId, 2, taskId,
                List.of(entry.id()), poolWithRenamedLogin());

        assertEquals(0, outcome.filed());
        assertEquals(1, outcome.skipped());
        assertEquals(0, ticketCount(projectId), "no evidence, no ticket");
    }

    @Test
    void crossProjectRefusalNeverBreaksTheSiblingRows() {
        long projectId = createProject();
        long otherProjectId = createProject();
        long taskId = exploreTask(projectId);
        WebElementView mine = confirmed(projectId, "登录", "http://shop.local/cart",
                List.of(new WebElementAnchorInput("testid", "old", 0.9, null)));
        // Same pool text matches an entry of ANOTHER project: the record door
        // refuses it as a cross-project re-point; my row must still land.
        WebElementView theirs = confirmed(otherProjectId, "登录", "http://shop.local/cart",
                List.of(new WebElementAnchorInput("testid", "old", 0.9, null)));

        ExploreReanchorProposal.Outcome outcome = proposal.file(projectId, 1, taskId,
                List.of(theirs.id(), mine.id()), poolWithRenamedLogin());

        assertEquals(1, outcome.filed());
        assertEquals(1, outcome.skipped());
        Map<String, Object> row = onlyTicket(projectId);
        assertEquals(mine.id(), ((Number) row.get("element_id")).longValue());
        assertEquals(0, ticketCount(otherProjectId));
    }

    @Test
    void emptyMismatchSetIsAZeroWriteNoop() {
        long projectId = createProject();
        long taskId = exploreTask(projectId);

        ExploreReanchorProposal.Outcome outcome = proposal.file(projectId, 1, taskId,
                List.of(), poolWithRenamedLogin());

        assertEquals(0, outcome.filed());
        assertEquals(0, outcome.skipped());
        assertEquals(0, ticketCount(projectId));
    }

    @Test
    void execTaskIdIsReadFromTheStepResultAndMissingMeansNoTicket() {
        assertEquals(4112L, ExploreReanchorProposal.readExecTaskId(
                "{\"ok\":true,\"execTaskId\":4112,\"observation\":\"...\"}"));
        assertEquals(0L, ExploreReanchorProposal.readExecTaskId("{\"ok\":true}"));
        assertEquals(0L, ExploreReanchorProposal.readExecTaskId(null));
        assertEquals(0L, ExploreReanchorProposal.readExecTaskId("not-json"));
    }

    private int ticketCount(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_step_suggestion WHERE project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }
}

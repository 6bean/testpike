package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.domain.service.impl.WebElementLedgerDraftStrategy;
import com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerPosting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WE-S6 census migration posting door : the
 * survey's ranked elements become ONE PENDING CARD PER ELEMENT for a human
 * to approve — filing cards never writes the ledger or rewrites scripts
 * (red line: the draft card gate stays the only write path). Reposting the
 * same census must not double-file a still-pending element, and the
 * confirmed card lands through the SAME {@code WebElementService.create}
 * chain the ledger page uses (no second write path, R40).
 * <p>Real dev database, no mocks. Fixtures create their own project and
 * rows; cleanup deletes only what this class created.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementCensusMigrationServiceTest {

    private static final String LOGIN_PAGE = "http://censusmigrate.test/login";
    private static final String KIND = "WEB_ELEMENT_WRITE";

    @Autowired
    private WebElementCensusMigrationService migrationService;

    @Autowired
    private WebElementLedgerDraftStrategy ledgerDraftStrategy;

    @Autowired
    private JdbcTemplate jdbc;

    private Long projectId;

    @BeforeEach
    void setUp() {
        String code = "cmg" + System.nanoTime();
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "存量成账草案测试项目-" + code, code);
        projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM tp_ai_chat_draft WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_script WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbc.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void seedScript(String css) {
        Long caseId = jdbc.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title, end_type) "
                        + "VALUES (?, ?, '成账夹具用例', 'web') RETURNING id",
                Long.class, "TC-CMG-" + System.nanoTime(), projectId);
        String actionJson = "{\"endType\":\"web\",\"setup\":[{\"action\":\"goto\","
                + "\"target\":{\"url\":\"" + LOGIN_PAGE + "\"}}],\"steps\":[{"
                + "\"action\":\"click\",\"target\":{\"css\":\"" + css + "\"}}]}";
        jdbc.update("INSERT INTO tp_script (case_id, schema_version, action_json, "
                + "status) VALUES (?, 'v2', ?::jsonb, 2)", caseId, actionJson);
    }

    private int pendingCards() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_draft WHERE project_id = ? "
                        + "AND object_type = ? AND status = 1",
                Integer.class, projectId, KIND);
        return count == null ? 0 : count;
    }

    @Test
    void postingFilesOnePendingCardPerElementAndWritesNothing() {
        seedScript("#login-btn");
        seedScript("#remember-me");

        WebElementLedgerPosting posting = migrationService.postLedgerDrafts(projectId);

        assertEquals(2, posting.entries(), "two distinct elements should surface");
        assertEquals(2, posting.posted());
        assertEquals(0, posting.alreadyPending());
        assertEquals(2, pendingCards());
        // Posting is a proposal only: the ledger and the scripts stay untouched.
        assertEquals(0, ledgerRows());
        List<String> payloads = jdbc.queryForList(
                "SELECT s.action_json::text FROM tp_script s "
                        + "JOIN tp_test_case c ON c.id = s.case_id "
                        + "WHERE c.project_id = ?", String.class, projectId);
        assertEquals(2, payloads.size());
        assertTrue(payloads.stream().allMatch(p -> p.contains("\"css\"")),
                "inline locators must survive untouched until a human confirms");
    }

    @Test
    void repostingKeepsExactlyOnePendingCardPerElement() {
        seedScript("#login-btn");
        seedScript("#remember-me");

        migrationService.postLedgerDrafts(projectId);
        WebElementLedgerPosting second = migrationService.postLedgerDrafts(projectId);

        assertEquals(0, second.posted(), "still-pending cards must not double-file");
        assertEquals(2, second.alreadyPending());
        assertEquals(2, pendingCards());
    }

    @Test
    void oneElementRepeatedAcrossScriptsBecomesOneCard() {
        seedScript("#dup-btn");
        seedScript("#dup-btn");

        WebElementLedgerPosting posting = migrationService.postLedgerDrafts(projectId);

        assertEquals(1, posting.entries());
        assertEquals(1, posting.posted());
    }

    @Test
    void confirmedCardLandsThroughTheLedgerCreateChain() {
        seedScript("#login-btn");
        migrationService.postLedgerDrafts(projectId);
        Map<String, Object> draftRow = jdbc.queryForMap(
                "SELECT id, project_id, object_type, payload_json::text AS payload_json "
                        + "FROM tp_ai_chat_draft WHERE project_id = ? AND object_type = ? "
                        + "AND status = 1", projectId, KIND);

        Integer landed = ledgerDraftStrategy.land(draftRow, 1L);

        assertEquals(1, ledgerRows(), "the confirmed card creates exactly one ledger row");
        assertEquals(0, landedVersion(landed), "ledger rows carry no version ledger");
        String stored = jdbc.queryForObject(
                "SELECT page_key || '|' || anchors::text FROM tp_web_element "
                        + "WHERE project_id = ?", String.class, projectId);
        assertTrue(stored.contains("#login-btn"), "the inline locator becomes a stored anchor");
        assertTrue(stored.startsWith(String.valueOf(
                        (String) jdbc.queryForMap(
                                "SELECT payload_json::jsonb->>'pageKey' AS pk FROM tp_ai_chat_draft "
                                        + "WHERE project_id = ? AND object_type = ? LIMIT 1",
                                projectId, KIND).get("pk"))),
                "the ledger page key must match the census-normalized page key");
    }

    @Test
    void blankProjectIsRefusedBeforeAnyDrafting() {
        assertThrows(ServiceException.class, () -> migrationService.postLedgerDrafts(0L));
        assertEquals(0, pendingCards());
    }

    private int ledgerRows() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_web_element WHERE project_id = ?",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }

    private static int landedVersion(Integer landed) {
        return landed == null ? 0 : landed;
    }
}

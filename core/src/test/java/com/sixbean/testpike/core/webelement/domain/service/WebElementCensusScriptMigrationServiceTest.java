package com.sixbean.testpike.core.webelement.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.aichat.domain.service.impl.WebElementLedgerDraftStrategy;
import com.sixbean.testpike.core.aichat.domain.service.impl.WebElementScriptRefDraftStrategy;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementScriptRefPosting;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WE-S6 script-ref replacement cards : once an
 * element is in the page element library, every script that repeats the
 * hand-written locator gets its OWN pending replacement card — confirming
 * the card lifts the script one new version whose target is the
 * {@code {{ELEM:name}}} reference dialect, while the inline payload stays
 * untouched until a human confirms (red line: the draft card gate is the
 * only write path). Posting also excludes, before filing anything: scripts
 * whose slots are already reference-form, and (page,name) pairs that still
 * have a pending card. The pre-replacement payload rides inside the card,
 * so per-case rollback re-applies it through the SAME saveRepaired chain —
 * version line keeps every step (diff trail).
 * <p>Real dev database, no mocks. Fixtures create their own project and
 * rows; cleanup deletes only what this class created.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementCensusScriptMigrationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LOGIN_PAGE = "http://scriptref.test/login";
    private static final String KIND = "WEB_ELEMENT_SCRIPT_REF";
    private static final String LEDGER_KIND = "WEB_ELEMENT_WRITE";

    @Autowired
    private WebElementCensusScriptMigrationService scriptMigrationService;
    @Autowired
    private WebElementCensusMigrationService ledgerMigrationService;
    @Autowired
    private WebElementLedgerDraftStrategy ledgerDraftStrategy;
    @Autowired
    private WebElementScriptRefDraftStrategy scriptRefDraftStrategy;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long projectId;

    @BeforeEach
    void setUp() {
        String code = "srg" + System.nanoTime();
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "引用替换草案测试项目-" + code, code);
        projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM tp_script_version WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbc.update("DELETE FROM tp_ai_chat_draft WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_script WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbc.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** One web case whose only locator step clicks the given css selector. */
    private long seedScript(String css) {
        Long caseId = jdbc.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title, end_type) "
                        + "VALUES (?, ?, '引用替换夹具用例', 'web') RETURNING id",
                Long.class, "TC-SRG-" + System.nanoTime(), projectId);
        jdbc.update("INSERT INTO tp_script (case_id, schema_version, action_json, "
                        + "status, version) VALUES (?, 'v2', ?::jsonb, 2, 1)",
                caseId, actionJson(css));
        return caseId;
    }

    private static String actionJson(String locator) {
        return "{\"endType\":\"web\",\"setup\":[{\"action\":\"goto\","
                + "\"target\":{\"url\":\"" + LOGIN_PAGE + "\"}}],\"steps\":[{"
                + "\"action\":\"click\",\"target\":{\"css\":\"" + locator + "\"}}]}";
    }

    /** File and land every pending ledger card so the elements enter the library. */
    private void landAllLedgerCards() {
        ledgerMigrationService.postLedgerDrafts(projectId);
        for (Map<String, Object> row : ledgerCardRows()) {
            ledgerDraftStrategy.land(row, 1L);
            jdbc.update("UPDATE tp_ai_chat_draft SET status = 2 WHERE id = ?",
                    ((Number) row.get("id")).longValue());
        }
    }

    private List<Map<String, Object>> ledgerCardRows() {
        return jdbc.queryForList("SELECT id, project_id, object_type, "
                + "payload_json::text AS payload_json FROM tp_ai_chat_draft "
                + "WHERE project_id = ? AND object_type = ? AND status = 1",
                projectId, LEDGER_KIND);
    }

    private List<Map<String, Object>> refCardRows() {
        return jdbc.queryForList("SELECT id, project_id, target_id, object_type, "
                + "payload_json::text AS payload_json FROM tp_ai_chat_draft "
                + "WHERE project_id = ? AND object_type = ? AND status = 1 "
                + "ORDER BY id", projectId, KIND);
    }

    private int refCards() {
        return refCardRows().size();
    }

    private String storedActionJson(long caseId) {
        return jdbc.queryForObject("SELECT action_json::text FROM tp_script "
                + "WHERE case_id = ?", String.class, caseId);
    }

    @Test
    void replacementCardsRideOnlyOnElementsAlreadyInTheLibrary() {
        long first = seedScript("#login-btn");
        long second = seedScript("#login-btn");
        long other = seedScript("#other-btn");

        WebElementScriptRefPosting beforeLedger =
                scriptMigrationService.postScriptRefDrafts(projectId);
        assertEquals(0, beforeLedger.posted(),
                "nothing is replaceable while the element is not in the library");
        assertEquals(2, beforeLedger.skippedNotLedgered());
        assertEquals(0, refCards());

        landAllLedgerCards();

        WebElementScriptRefPosting posting =
                scriptMigrationService.postScriptRefDrafts(projectId);
        assertEquals(2, posting.entries());
        assertEquals(3, posting.scriptsScanned());
        assertEquals(3, posting.posted());
        assertEquals(3, refCards());
        for (Map<String, Object> card : refCardRows()) {
            String payload = (String) card.get("payload_json");
            assertTrue(payload.contains("{{ELEM:"),
                    "the proposed payload is reference form: " + payload);
        }
        // Filing cards touches no script: inline locators stay unchanged.
        assertTrue(sameJson(actionJson("#login-btn"), storedActionJson(first)));
        assertTrue(sameJson(actionJson("#login-btn"), storedActionJson(second)));
        assertTrue(sameJson(actionJson("#other-btn"), storedActionJson(other)));
    }

    @Test
    void repostingSkipsPendingCardsAndAlreadyReplacedScripts() {
        seedScript("#login-btn");
        seedScript("#other-btn");
        landAllLedgerCards();
        scriptMigrationService.postScriptRefDrafts(projectId);

        WebElementScriptRefPosting replay = scriptMigrationService.postScriptRefDrafts(projectId);
        assertEquals(0, replay.posted());
        assertEquals(2, replay.alreadyPending());

        landAllRefCards();

        WebElementScriptRefPosting afterReplace =
                scriptMigrationService.postScriptRefDrafts(projectId);
        assertEquals(0, afterReplace.posted());
        assertEquals(0, afterReplace.entries(),
                "the survey itself stops flagging fully replaced scripts");
    }

    @Test
    void confirmedCardLandsOneNewReferenceVersionAndRollbackRidesTheSameChain() {
        long caseId = seedScript("#login-btn");
        landAllLedgerCards();
        scriptMigrationService.postScriptRefDrafts(projectId);
        Map<String, Object> card = refCardRows().get(0);
        String payload = (String) card.get("payload_json");

        Integer landedVersion = scriptRefDraftStrategy.land(card, 1L);

        ScriptInfo current = scriptService.getByCaseId(caseId);
        assertEquals(current.version(), landedVersion,
                "landing returns exactly the new script version");
        assertEquals(2, current.version());
        assertTrue(storedActionJson(caseId).contains(
                        "{{ELEM:" + payloadOfField(payload, "elementName") + "}}"),
                "the stored target becomes the ledger reference");
        assertFalse(storedActionJson(caseId).contains("\"css\""),
                "the hand-written locator is gone from the new version");
        // Diff trail: the version line carries the post-repair snapshot and
        // the card itself carries the pre-replacement payload.
        Integer snapshots = jdbc.queryForObject(
                "SELECT count(*) FROM tp_script_version WHERE case_id = ?",
                Integer.class, caseId);
        assertTrue(snapshots != null && snapshots >= 1, "version line keeps a snapshot");
        assertTrue(payload.contains("\"beforeActionJson\""));

        ScriptInfo rolledBack = scriptService.saveRepaired(caseId, "v2",
                payloadOfField(payload, "beforeActionJson"));
        assertTrue(sameJson(actionJson("#login-btn"), rolledBack.actionJson()),
                "per-case rollback re-applies the card's own pre-replacement payload");
        assertEquals(3, rolledBack.version());
    }

    @Test
    void staleScriptVersionRefusesLandingAndForceOverridesInOneClick() {
        long caseId = seedScript("#login-btn");
        landAllLedgerCards();
        scriptMigrationService.postScriptRefDrafts(projectId);
        Map<String, Object> card = refCardRows().get(0);

        jdbc.update("UPDATE tp_script SET version = 5 WHERE case_id = ?", caseId);
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> scriptRefDraftStrategy.land(card, 1L));
        assertTrue(conflict.getMessage().contains("引用替换"), conflict.getMessage());
        assertTrue(storedActionJson(caseId).contains("\"css\""),
                "a refused landing writes nothing");

        Integer forced = scriptRefDraftStrategy.land(card, 1L,
                DraftWriteStrategy.ACTION_FORCE);
        assertEquals(6, forced, "the one-click override replays onto the current line");
        assertTrue(storedActionJson(caseId).contains("{{ELEM:"));
    }

    @Test
    void retiredElementRefusesLandingWithHonestGuidance() {
        long caseId = seedScript("#login-btn");
        landAllLedgerCards();
        scriptMigrationService.postScriptRefDrafts(projectId);
        Map<String, Object> card = refCardRows().get(0);
        jdbc.update("UPDATE tp_web_element SET state = 3 WHERE project_id = ?", projectId);

        ServiceException refused = assertThrows(ServiceException.class,
                () -> scriptRefDraftStrategy.land(card, 1L));
        assertTrue(refused.getMessage().contains("login-btn"), refused.getMessage());
        assertTrue(storedActionJson(caseId).contains("\"css\""),
                "a landing cut against a retired element writes nothing");
    }

    @Test
    void blankProjectIsRefusedBeforeAnyCard() {
        assertThrows(ServiceException.class,
                () -> scriptMigrationService.postScriptRefDrafts(0L));
        assertEquals(0, refCards());
    }

    private void landAllRefCards() {
        for (Map<String, Object> card : refCardRows()) {
            scriptRefDraftStrategy.land(card, 1L);
            jdbc.update("UPDATE tp_ai_chat_draft SET status = 2 WHERE id = ?",
                    ((Number) card.get("id")).longValue());
        }
    }

    /** Reads one textual field out of the structured card payload via Postgres. */
    private String payloadOfField(String payloadJson, String field) {
        return jdbc.queryForObject(
                "SELECT (?::jsonb)->>? AS v", String.class, payloadJson, field);
    }

    /** JSON-equality on documents whose stored form went through jsonb. */
    private static boolean sameJson(String left, String right) {
        try {
            return MAPPER.readTree(left).equals(MAPPER.readTree(right));
        } catch (Exception unparsable) {
            return false;
        }
    }
}

package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorRolloutView;
import com.sixbean.testpike.core.behavior.domain.service.BehaviorOutcomeService;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 gray-release pairing over the outcome ledger .
 * The monitor resolves the live gray version of a template family, pairs it
 * with its control (the current full version, else the previous gray), and
 * reports the degradation advisory — advisory only, no automatic action and
 * no template status change (red line: humans stay in the loop).
 * Test templates are tagged 'ut-rollout-' and deleted per case; the ledger
 * rows are tagged the same way (R37: factory tables are never truncated).
 */
@SpringBootTest(classes = CoreApplication.class)
class BehaviorRolloutMonitorTest {

    private static final String TAG = "ut-rollout-";
    private static final String DOMAIN = "ut-rollout-domain";
    private static final long PROJECT = 9_999_998L;
    /** Reserved high id band for synthetic adoption cases (never real tp_test_case ids). */
    private static final long ADOPTION_CASE_FIRST = 9_880_001L;

    @Autowired
    private BehaviorRolloutMonitor monitor;

    @Autowired
    private BehaviorOutcomeService outcomeService;

    @Autowired
    private PromptTemplateService templateService;

    @Autowired
    private BehaviorOutcomeIntake intake;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUpTestRows() {
        jdbc.update("DELETE FROM tp_ai_behavior_outcome WHERE source_event_id LIKE ?", TAG + "%");
        jdbc.update("DELETE FROM tp_ai_behavior_outcome WHERE source = 'adoption' AND template_domain = ?", DOMAIN);
        jdbc.update("DELETE FROM tp_ai_task WHERE template_version_id IN"
                + " (SELECT id FROM tp_prompt_template WHERE name LIKE ?)", TAG + "%");
        jdbc.update("DELETE FROM tp_prompt_template WHERE name LIKE ?", TAG + "%");
    }

    /** Seed one family version straight onto the table (status 1=gray, 2=full). */
    private long seedVersion(String name, int version, int status) {
        jdbc.update("""
                INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by)
                VALUES (?, ?, ?, ?, ?, 1)
                """, name, DOMAIN, version, "content v" + version, status);
        return jdbc.queryForObject("SELECT id FROM tp_prompt_template WHERE name = ? AND version = ?",
                Long.class, name, version);
    }

    private void seedOutcomes(long versionId, int adopted, int deprecated) {
        for (int i = 0; i < adopted; i++) {
            outcomeService.record(new com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeEvent(
                    DOMAIN, versionId, "task", "ref-" + versionId + "-a" + i,
                    "review", TAG + "rev-" + versionId + "-a" + i, "adopted", BigDecimal.ONE, PROJECT));
        }
        for (int i = 0; i < deprecated; i++) {
            outcomeService.record(new com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeEvent(
                    DOMAIN, versionId, "task", "ref-" + versionId + "-d" + i,
                    "review", TAG + "rev-" + versionId + "-d" + i, "deprecated", BigDecimal.ONE, PROJECT));
        }
    }

    @Test
    void grayReleasePairsWithCurrentFullVersionAndAdvisesOnDrop() {
        String name = TAG + "drop-family";
        long full = seedVersion(name, 1, 2);
        long gray = seedVersion(name, 2, 1);
        // control 10/10 adopted = rate 1.0; candidate 3/10 adopted = rate 0.3 → drop 70pp > 10pp default
        seedOutcomes(full, 10, 0);
        seedOutcomes(gray, 3, 7);
        BehaviorRolloutView view = monitor.advisoryFor(DOMAIN, name);
        assertNotNull(view, "a live gray release must produce a rollout view");
        assertEquals(gray, view.grayId(), "view points at the gray row");
        assertEquals(full, view.controlId(), "control resolves to the current full version");
        assertNotNull(view.advisory(), "both sides carry outcome data so the advisory is computed");
        assertTrue(view.advisory().rollbackAdvisory(), "70pp drop exceeds the 10pp tolerance");
        assertNotNull(view.advisory().dropPercentagePoints());
        assertEquals(0, new BigDecimal("70.00").compareTo(view.advisory().dropPercentagePoints()));
        // The advisory never touches the template: gray stays gray.
        PromptTemplateItem stillGray = templateService.latestByStatus(name, DOMAIN, 1);
        assertNotNull(stillGray, "monitoring must not promote or roll back anything");
    }

    @Test
    void adoptionEditAndDeleteObservationsDriveTheDegradationAdvisory() {
        String name = TAG + "adoption-family";
        long full = seedVersion(name, 1, 2);
        long gray = seedVersion(name, 2, 1);
        seedOutcomes(full, 10, 0); // control rate 1.0
        // One producing task per synthetic case pins the gray version so the
        // intake traces adoption rows onto the candidate (real ledger path of
        // the observer's two channels: CASE_ADOPTION_EDIT / CASE_ADOPTION_DELETE).
        for (int i = 0; i < 10; i++) {
            long caseId = ADOPTION_CASE_FIRST + i;
            jdbc.update("INSERT INTO tp_ai_task (project_id, operator_id, task_type, status,"
                            + " case_id, template_version_id) VALUES (?, 1, 1, 3, ?, ?)",
                    PROJECT, caseId, gray);
            if (i < 6) {
                intake.recordDerivedObservation(OutcomeChannel.CASE_ADOPTION_EDIT,
                        caseId + ":v1", "corrected", String.valueOf(caseId), PROJECT);
            } else {
                intake.recordDerivedObservation(OutcomeChannel.CASE_ADOPTION_DELETE,
                        String.valueOf(caseId), "deprecated", String.valueOf(caseId), PROJECT);
            }
        }
        Integer pinned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_behavior_outcome WHERE source = 'adoption'"
                        + " AND template_version_id = ?", Integer.class, gray);
        assertEquals(10, pinned, "both adoption forms must land pinned to the gray version");
        BehaviorRolloutView view = monitor.advisoryFor(DOMAIN, name);
        assertNotNull(view, "the live gray row is always reported");
        assertNotNull(view.advisory(), "adoption rows count into the candidate result rate");
        assertTrue(view.advisory().rollbackAdvisory(),
                "six corrections (0.5 each) + four rejections (0) = rate 0.30, 70pp behind control");
        assertEquals(0, new BigDecimal("70.00").compareTo(view.advisory().dropPercentagePoints()),
                "the measured drop is reported in percentage points");
        assertTrue(view.advisory().note().toLowerCase().contains("rollback"),
                "the note names the human action it suggests");
    }

    @Test
    void withoutGrayReleaseThereIsNothingToAdvise() {
        String name = TAG + "no-gray-family";
        seedVersion(name, 1, 2);
        assertNull(monitor.advisoryFor(DOMAIN, name),
                "a family without a live gray row has no candidate to judge");
    }

    @Test
    void candidateWithoutControlDataReportsIncompleteComparisonNotSilence() {
        String name = TAG + "first-gray-family";
        long gray = seedVersion(name, 2, 1); // single gray row: no full, no earlier gray
        seedOutcomes(gray, 4, 1);
        BehaviorRolloutView view = monitor.advisoryFor(DOMAIN, name);
        assertNotNull(view, "gray rows are always reported so the caller can see why nothing was judged");
        assertNull(view.controlId(), "no full version and no previous gray means no control");
        assertNotNull(view.advisory());
        assertFalse(view.advisory().rollbackAdvisory(), "without data on both sides nothing may be advised");
        assertTrue(view.advisory().note().contains("no outcome rows"), "the note names the missing side");
    }

    @Test
    void earlierGrayBecomesControlWhenNoFullReleaseExists() {
        String name = TAG + "two-gray-family";
        long first = seedVersion(name, 1, 1);
        long second = seedVersion(name, 2, 1);
        seedOutcomes(first, 5, 0);   // control rate 1.0
        seedOutcomes(second, 5, 0);  // candidate rate 1.0 → drop 0pp, inside tolerance
        BehaviorRolloutView view = monitor.advisoryFor(DOMAIN, name);
        assertEquals(first, view.controlId(), "the previous gray row of the family is the control");
        assertEquals(second, view.grayId(), "the newest gray row is the live candidate");
        assertNotNull(view.advisory());
        assertFalse(view.advisory().rollbackAdvisory(), "equal rates never advise a rollback");
    }

    @Test
    void blankArgumentsAreRejectedNotSilentlySkipped() {
        assertThrows(ValidationException.class, () -> monitor.advisoryFor(" ", "x"));
        assertThrows(ValidationException.class, () -> monitor.advisoryFor(DOMAIN, "  "));
    }
}

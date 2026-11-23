package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Outcome intake wiring contract : every human review
 * verdict — case review, requirement review, judge override — reaches the
 * outcome ledger with the producing prompt-template version pinned through
 * tp_ai_task when a task exists, and with the channel default domain plus a
 * NULL version when no task can be pinned. Event keys are namespaced per
 * channel table so two channels that happen to share a numeric row id can
 * never swallow each other's verdict. The intake is an observer: bad data or a
 * ledger rejection must never throw into the caller's review flow.
 * Runs against the real dev DB of this chain's slot (strict testing: no mocks
 * on service interfaces); fixtures are tagged and removed per case (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class BehaviorOutcomeIntakeTest {

    private static final String TPL_NAME = "ut-intake-tpl";
    private static final String TPL_DOMAIN = "ut-intake-domain";
    private static final long PROJECT = 9_999_999L;
    private static final long CASE_REF = 9_100_001L;
    private static final long REQUIREMENT_REF = 9_100_011L;

    @Autowired
    private BehaviorOutcomeIntake intake;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUpTestRows() {
        jdbc.update("DELETE FROM tp_ai_behavior_outcome WHERE source_event_id LIKE '%:9100%'");
        jdbc.update("DELETE FROM tp_ai_task WHERE project_id = ? AND case_id = ? OR requirement_id = ?",
                PROJECT, CASE_REF, REQUIREMENT_REF);
        jdbc.update("DELETE FROM tp_prompt_template WHERE name = ?", TPL_NAME);
    }

    private Long newTemplateRow() {
        jdbc.update("INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by)"
                        + " VALUES (?, ?, 1, 'ut intake fixture', 2, 1)",
                TPL_NAME, TPL_DOMAIN);
        return jdbc.queryForObject("SELECT id FROM tp_prompt_template WHERE name = ? AND domain = ?",
                Long.class, TPL_NAME, TPL_DOMAIN);
    }

    private void newTask(Long templateVersionId, Long caseId, Long requirementId) {
        jdbc.update("INSERT INTO tp_ai_task (project_id, operator_id, task_type, status,"
                        + " template_version_id, case_id, requirement_id)"
                        + " VALUES (?, 1, 1, 3, ?, ?, ?)",
                PROJECT, templateVersionId, caseId, requirementId);
    }

    private Map<String, Object> outcomeRow(String sourceEventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT template_domain, template_version_id, scope, outcome, weight, source"
                        + " FROM tp_ai_behavior_outcome WHERE source_event_id = ?",
                sourceEventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void caseReviewPinsTemplateDomainAndVersionThroughTheProducingTask() {
        Long versionId = newTemplateRow();
        newTask(versionId, CASE_REF, null);
        intake.recordReviewVerdict(OutcomeChannel.CASE_REVIEW, 9_100_501L, "adopted",
                String.valueOf(CASE_REF), PROJECT);
        Map<String, Object> row = outcomeRow("case-review:9100501");
        assertEquals(TPL_DOMAIN, row.get("template_domain"));
        assertEquals(versionId, row.get("template_version_id"));
        assertEquals("task", row.get("scope"));
    }

    @Test
    void requirementReviewPinsThroughRequirementBackref() {
        Long versionId = newTemplateRow();
        newTask(versionId, null, REQUIREMENT_REF);
        intake.recordReviewVerdict(OutcomeChannel.REQUIREMENT_REVIEW, 9_100_502L, "corrected",
                String.valueOf(REQUIREMENT_REF), PROJECT);
        Map<String, Object> row = outcomeRow("requirement-review:9100502");
        assertEquals(TPL_DOMAIN, row.get("template_domain"));
        assertEquals(versionId, row.get("template_version_id"));
        assertEquals("document", row.get("scope"));
    }

    @Test
    void verdictWithoutPinningTaskFallsBackToChannelDefaultDomainAndNullVersion() {
        intake.recordReviewVerdict(OutcomeChannel.CASE_REVIEW, 9_100_503L, "deprecated",
                "9100002", PROJECT); // no tp_ai_task row carries this case id
        Map<String, Object> row = outcomeRow("case-review:9100503");
        assertEquals(OutcomeChannel.CASE_REVIEW.defaultTemplateDomain(), row.get("template_domain"));
        assertNull(row.get("template_version_id"));
    }

    @Test
    void replayedVerdictCountsExactlyOnce() {
        intake.recordReviewVerdict(OutcomeChannel.JUDGE_REVIEW, 9_100_504L, "corrected", "9100003", null);
        intake.recordReviewVerdict(OutcomeChannel.JUDGE_REVIEW, 9_100_504L, "corrected", "9100003", null);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_behavior_outcome WHERE source_event_id = ?",
                Integer.class, "judge-review:9100504");
        assertEquals(1, count);
    }

    @Test
    void sameNumericEventIdAcrossChannelsRecordsBothRows() {
        intake.recordReviewVerdict(OutcomeChannel.CASE_REVIEW, 9_100_505L, "adopted", "9100004", null);
        intake.recordReviewVerdict(OutcomeChannel.REQUIREMENT_REVIEW, 9_100_505L, "adopted", "9100005", null);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_behavior_outcome WHERE source_event_id IN (?, ?)",
                Integer.class, "case-review:9100505", "requirement-review:9100505");
        assertEquals(2, count);
    }

    @Test
    void malformedVerdictIsSwallowedAndNeverReachesTheLedger() {
        assertDoesNotThrow(() -> intake.recordReviewVerdict(
                OutcomeChannel.CASE_REVIEW, 9_100_506L, "ut-bogus", "9100006", null));
        assertNull(outcomeRow("case-review:9100506"));
        assertDoesNotThrow(() -> intake.recordReviewVerdict(
                OutcomeChannel.REQUIREMENT_REVIEW, null, "adopted", "9100007", null));
    }

    // ---- chip / draft channels ----

    @Test
    void chipVerdictPinsThroughRequirementBackrefAndCarriesDocumentScope() {
        Long versionId = newTemplateRow();
        newTask(versionId, null, REQUIREMENT_REF);
        intake.recordReviewVerdict(OutcomeChannel.CHIP, 9_100_511L, "adopted",
                String.valueOf(REQUIREMENT_REF), PROJECT);
        Map<String, Object> row = outcomeRow("chip-confirm:9100511");
        assertEquals(TPL_DOMAIN, row.get("template_domain"));
        assertEquals(versionId, row.get("template_version_id"));
        assertEquals("document", row.get("scope"));
        assertEquals("chip", row.get("source"));
    }

    @Test
    void draftVerdictHasNoTaskLineageAndKeepsChannelDefaultDomain() {
        intake.recordReviewVerdict(OutcomeChannel.DRAFT, 9_100_512L, "adopted", "9100008", PROJECT);
        Map<String, Object> row = outcomeRow("draft-apply:9100512");
        assertEquals(OutcomeChannel.DRAFT.defaultTemplateDomain(), row.get("template_domain"));
        assertNull(row.get("template_version_id"));
        assertEquals("task", row.get("scope"));
        assertEquals("draft", row.get("source"));
    }

    @Test
    void replayedChipAndDraftVerdictsCountExactlyOnce() {
        intake.recordReviewVerdict(OutcomeChannel.CHIP, 9_100_513L, "adopted", "9100009", null);
        intake.recordReviewVerdict(OutcomeChannel.CHIP, 9_100_513L, "adopted", "9100009", null);
        intake.recordReviewVerdict(OutcomeChannel.DRAFT, 9_100_513L, "adopted", "9100010", null);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_behavior_outcome WHERE source_event_id IN (?, ?)",
                Integer.class, "chip-confirm:9100513", "draft-apply:9100513");
        assertEquals(2, count, "each channel keeps its own replay-guarded row");
    }
}

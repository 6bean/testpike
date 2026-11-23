package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.behavior.domain.component.AdoptionFormDeriver;
import com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake;
import com.sixbean.testpike.core.behavior.domain.component.OutcomeChannel;
import com.sixbean.testpike.core.behavior.domain.component.RevisionSamplePool;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshot;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns human handling of an AI-generated case into adoption ledger rows
 * : the producing AI text is the v1 content
 * snapshot of tp_case_version (create writes it in the same transaction as
 * the row), the reaction is the current content after an edit, or the delete
 * itself. Classification rides {@link AdoptionFormDeriver} and recording rides
 * the shared {@link BehaviorOutcomeIntake} channels CASE_ADOPTION_EDIT /
 * CASE_ADOPTION_DELETE — same table and same assembly as the review verdicts
 * (R40 single source), so the rollout monitor aggregates adoption and review
 * observations per template version without a second ledger.
 *
 * <p>Correction-family observations additionally feed the project-level
 * few-shot revision pool : the before/after pair of the AI text
 * and the human result lands in {@link RevisionSamplePool} so generation can
 * learn from what humans fixed. Only corrected/deprecated pairs enter; the
 * pool write rides the same event key as the ledger row, so both dedup alike.</p>
 *
 * <p>Pure statistics, zero interception: every failure mode (missing v1
 * snapshot, corrupted JSON, ledger rejection) degrades to a WARN line; the
 * edit or delete of the case itself never depends on this observation.</p>
 *
 * <p>Only source=1 (AI generated) rows are observed: template/import/clone
 * material never came out of a model, so counting its edits would poison the
 * adoption rate. Metadata-only touches are skipped by the caller through the
 * existing content-family predicate, keeping one gate for both the invalidation
 * chain and this observation.</p>
 */
@Component
public class AiOutputAdoptionObserver {

    private static final Logger log = LoggerFactory.getLogger(AiOutputAdoptionObserver.class);

    /** tp_test_case.source value 1 = AI generated (CaseInfo column comment). */
    static final int SOURCE_AI_GENERATED = 1;

    private final JdbcTemplate jdbcTemplate;
    private final AdoptionFormDeriver deriver;
    private final BehaviorOutcomeIntake intake;
    private final RevisionSamplePool revisionPool;

    public AiOutputAdoptionObserver(JdbcTemplate jdbcTemplate, AdoptionFormDeriver deriver,
                                    BehaviorOutcomeIntake intake, RevisionSamplePool revisionPool) {
        this.jdbcTemplate = jdbcTemplate;
        this.deriver = deriver;
        this.intake = intake;
        this.revisionPool = revisionPool;
    }

    /**
     * One observation per edit event: key "{caseId}:v{version}" is the natural
     * id of the event, so replaying the observation of a version lands exactly
     * once (ON CONFLICT DO NOTHING in the ledger core and in the pool).
     */
    public void observeEdit(CaseInfo edited) {
        if (edited == null || edited.source() != SOURCE_AI_GENERATED) {
            return;
        }
        try {
            String aiText = aiOriginalText(edited.id());
            if (aiText == null) {
                return; // no AI snapshot to compare against — nothing to observe
            }
            String editedText = renderContent(edited.title(), edited.precondition(),
                    edited.expectedResult(), descriptionsOfSteps(edited.steps()));
            AdoptionFormDeriver.AdoptionForm form = deriver.derive(aiText, editedText);
            if (form == null) {
                return;
            }
            String eventKey = edited.id() + ":v" + edited.version();
            intake.recordDerivedObservation(OutcomeChannel.CASE_ADOPTION_EDIT,
                    eventKey, form.outcome(), String.valueOf(edited.id()), edited.projectId());
            revisionPool.recordRevision(edited.id(), eventKey, form.outcome(),
                    aiText, editedText, edited.projectId());
        } catch (Exception e) {
            log.warn("adoption observation skipped for edited case {}: {}", edited.id(), e.toString());
        }
    }

    /**
     * A delete is the strongest rejection: deprecated by definition, no
     * similarity computed and no threshold read. Key "{caseId}" keeps the
     * deletion family separate from edit keys of the same case, and lands in
     * the pool with the human side left NULL.
     */
    public void observeDelete(long caseId, int source, Long projectId) {
        if (source != SOURCE_AI_GENERATED) {
            return;
        }
        try {
            String aiText = aiOriginalText(caseId);
            if (aiText == null) {
                return;
            }
            AdoptionFormDeriver.AdoptionForm form = deriver.derive(aiText, "");
            if (form == null) {
                return;
            }
            intake.recordDerivedObservation(OutcomeChannel.CASE_ADOPTION_DELETE,
                    String.valueOf(caseId), form.outcome(), String.valueOf(caseId), projectId);
            revisionPool.recordRevision(caseId, String.valueOf(caseId), form.outcome(),
                    aiText, null, projectId);
        } catch (Exception e) {
            log.warn("adoption observation skipped for deleted case {}: {}", caseId, e.toString());
        }
    }

    /**
     * The producing AI text = content fields of the v1 snapshot. A corrupted
     * snapshot decodes through the codec's IllegalArgumentException and is
     * treated as no observation (best-effort ledger, never blocks the chain).
     */
    private String aiOriginalText(long caseId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT snapshot_json::text FROM tp_case_version WHERE case_id = ? AND version = 1",
                (rs, rowNum) -> rs.getString(1), caseId);
        if (rows.isEmpty()) {
            return null;
        }
        CaseSnapshot snapshot = CaseSnapshotCodec.fromJson(rows.get(0));
        return renderContent(snapshot.title(), snapshot.precondition(), snapshot.expectedResult(),
                snapshot.steps() == null ? List.of()
                        : snapshot.steps().stream().map(s -> s.description()).toList());
    }

    /**
     * Canonical content rendering shared by both sides of the comparison:
     * title, precondition, expected result and step descriptions joined in
     * order. Metadata (priority, status, assignee, tags) is deliberately
     * excluded — an adoption signal is about the text the model wrote, not
     * about who the case was assigned to.
     */
    private static String renderContent(String title, String precondition, String expectedResult,
                                        List<String> stepDescriptions) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, title);
        appendLine(sb, precondition);
        appendLine(sb, expectedResult);
        for (String step : stepDescriptions) {
            appendLine(sb, step);
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(value == null ? "" : value);
    }

    private static List<String> descriptionsOfSteps(List<CaseStepInfo> steps) {
        return steps == null ? List.of() : steps.stream().map(CaseStepInfo::description).toList();
    }
}

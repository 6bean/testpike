package com.sixbean.testpike.core.behavior.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Project-level few-shot revision sample pool .
 * When a human corrects or discards an AI-generated case, the before/after
 * pair of the producing AI text and the human result lands here so the
 * generation few-shot segment can show "this is what humans fixed" material
 * next to the style references. Consumption rides the existing
 * StyleFewshotBridge switch — no second toggle and no second injection
 * channel (R40 single source).
 *
 * <p>Only the correction family feeds the pool: {@code corrected} (heavy
 * rewrite kept) and {@code deprecated} (deleted or rewritten past
 * recognition). Straight adoptions and light finishing edits are not
 * negative samples and are skipped without a log line — they are the normal
 * outcome, not a failure mode.</p>
 *
 * <p>Best-effort by design: a unique-key replay writes exactly once
 * (ON CONFLICT DO NOTHING) and any other failure degrades to a WARN line;
 * the observation must never block the edit or delete chain it watches.</p>
 */
@Component
public class RevisionSamplePool {

    private static final Logger log = LoggerFactory.getLogger(RevisionSamplePool.class);

    /** Ledger outcomes eligible as negative few-shot samples. */
    private static final String OUTCOME_CORRECTED = "corrected";
    private static final String OUTCOME_DEPRECATED = "deprecated";

    private final JdbcTemplate jdbcTemplate;

    public RevisionSamplePool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
 * Records one revision sample. The event key is the same composite the
 * adoption ledger uses for the very same observation ("{caseId}:v{version}"
 * for edits, "{caseId}" for deletions), so both sides dedup identically.
 *
 * @param caseId judged case id (pool rows stay traceable to the case)
 * @param eventKey dedup key of this observation
 * @param outcome corrected / deprecated (anything else is skipped)
 * @param aiText producing AI text (content-family rendering of v1)
 * @param humanText human result after the edit; NULL for a deletion
 * @param projectId owning project for the per-project pool segment
 */
    public void recordRevision(long caseId, String eventKey, String outcome,
                               String aiText, String humanText, Long projectId) {
        if (!OUTCOME_CORRECTED.equals(outcome) && !OUTCOME_DEPRECATED.equals(outcome)) {
            return;
        }
        if (eventKey == null || eventKey.isBlank() || aiText == null || aiText.isBlank()) {
            log.warn("revision sample skipped: caseId={}, eventKey={} (no AI side to learn from)",
                    caseId, eventKey);
            return;
        }
        try {
            jdbcTemplate.update("INSERT INTO tp_ai_fewshot_revision"
                            + " (project_id, case_id, event_key, outcome, ai_text, human_text)"
                            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (event_key) DO NOTHING",
                    projectId, caseId, eventKey, outcome, aiText, humanText);
        } catch (Exception e) {
            // Pure statistics: pool write failures never touch the caller's chain.
            log.warn("revision sample degraded (generation chain unaffected): caseId={}, eventKey={}",
                    caseId, eventKey, e);
        }
    }
}

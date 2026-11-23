package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeEvent;
import com.sixbean.testpike.core.behavior.domain.service.BehaviorOutcomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-source intake that turns real human verdicts into outcome ledger
 * events ( item 7 wiring; red line 6: every review channel calls this one
 * component, no per-channel copies). Assembly per verdict: resolve the
 * producing prompt-template lineage by tracing tp_ai_task (case_id /
 * requirement_id -> template_version_id -> tp_prompt_template.domain), fall
 * back to the channel default domain with a NULL version when no task pins
 * it, namespace the event key with the channel prefix, then record.
 * The intake never blocks the human decision path: null keys, unknown
 * outcome values and any ledger failure are logged as WARN and swallowed.
 * Ledger purity is best-effort by design — the rollback advisory it feeds is
 * advisory anyway (humans stay in the loop on template status).
 */
@Component
public class BehaviorOutcomeIntake {

    private static final Logger log = LoggerFactory.getLogger(BehaviorOutcomeIntake.class);

    /** Verdict vocabulary shared by the review family (ledger outcome values). */
    private static final Set<String> OUTCOMES = Set.of("adopted", "deprecated", "corrected", "partial");

    /** Maps a review action enum name onto the ledger outcome vocabulary. */
    private static final Map<String, String> REVIEW_OUTCOME = Map.of(
            "PASS", "adopted",
            "NEED_CHANGE", "corrected",
            "REJECT", "deprecated");

    private final NamedParameterJdbcTemplate named;
    private final BehaviorOutcomeService outcomeService;

    public BehaviorOutcomeIntake(NamedParameterJdbcTemplate named, BehaviorOutcomeService outcomeService) {
        this.named = named;
        this.outcomeService = outcomeService;
    }

    /**
     * Translates a review action enum name (PASS / NEED_CHANGE / REJECT) into
     * the ledger outcome; unknown values map to null so the intake skips them
     * instead of inventing a verdict.
     */
    public static String reviewOutcome(String actionName) {
        return actionName == null ? null : REVIEW_OUTCOME.get(actionName.toUpperCase());
    }

    /**
     * Records one review verdict. Safe to call inside the verdict transaction:
     * every failure mode (missing key, unknown outcome, ledger rejection) is
     * degraded to a WARN line, the caller's flow is untouched.
     *
     * @param channel       which decision door emitted the verdict
     * @param sourceEventId id of the verdict row inside its own channel table
     * @param outcome       adopted / deprecated / corrected / partial
     * @param scopeRef      business id of the judged material for trace-back
     * @param projectId     owning project for per-project bucketing, may be NULL
     */
    public void recordReviewVerdict(OutcomeChannel channel, Long sourceEventId,
                                    String outcome, String scopeRef, Long projectId) {
        if (channel == null || sourceEventId == null) {
            log.warn("outcome intake skipped: channel={}, sourceEventId={}", channel, sourceEventId);
            return;
        }
        recordVerdict(channel, String.valueOf(sourceEventId), outcome, scopeRef, projectId);
    }

    /**
 * Records one derived observation : the ledger rows this
 * channel feeds are not clicks but classifications of human handling of
 * AI-produced material, so the event key is a composite string owned by
 * the calling domain ("{caseId}:v{version}" for edits, "{caseId}" for
 * deletions). Same safe posture as {@link #recordReviewVerdict}: bad keys
 * and ledger failures degrade to a WARN line and never touch the caller.
 */
    public void recordDerivedObservation(OutcomeChannel channel, String sourceEventKey,
                                         String outcome, String scopeRef, Long projectId) {
        if (channel == null || sourceEventKey == null || sourceEventKey.isBlank()) {
            log.warn("outcome intake skipped: channel={}, sourceEventKey={}", channel, sourceEventKey);
            return;
        }
        recordVerdict(channel, sourceEventKey.trim(), outcome, scopeRef, projectId);
    }

    /** Shared assembly core: outcome vocabulary check, template tracing, then record. */
    private void recordVerdict(OutcomeChannel channel, String eventKey,
                               String outcome, String scopeRef, Long projectId) {
        try {
            if (outcome == null || !OUTCOMES.contains(outcome)) {
                log.warn("outcome intake skipped: channel={}, eventKey={}, outcome={}",
                        channel, eventKey, outcome);
                return;
            }
            Map<String, Object> trace = traceTemplate(channel.traceColumn(), scopeRef);
            String domain = trace != null && trace.get("domain") != null
                    ? (String) trace.get("domain") : channel.defaultTemplateDomain();
            Long versionId = trace == null ? null : ((Number) trace.get("version_id")).longValue();
            outcomeService.record(new BehaviorOutcomeEvent(domain, versionId, channel.scope(),
                    scopeRef, channel.source(), channel.eventKeyPrefix() + ":" + eventKey,
                    outcome, null, projectId));
        } catch (Exception e) {
            log.warn("outcome intake degraded (verdict not counted): channel={}, eventKey={}",
                    channel, eventKey, e);
        }
    }

    /**
     * Pins the producing template lineage: the newest ai task that references
     * the judged material, its pinned template version and that template's
     * domain. Returns null when the family has no task back-reference or the
     * material id is not numeric (manual rows never came from a task).
     */
    private Map<String, Object> traceTemplate(String traceColumn, String scopeRef) {
        if (traceColumn == null || scopeRef == null
                || scopeRef.isEmpty() || !scopeRef.chars().allMatch(Character::isDigit)) {
            return null;
        }
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT t.template_version_id AS version_id, pt.domain AS domain"
                        + " FROM tp_ai_task t LEFT JOIN tp_prompt_template pt ON pt.id = t.template_version_id"
                        + " WHERE t." + traceColumn + " = :ref AND t.template_version_id IS NOT NULL"
                        + " ORDER BY t.id DESC LIMIT 1",
                new MapSqlParameterSource("ref", Long.parseLong(scopeRef)));
        return rows.isEmpty() ? null : rows.get(0);
    }
}

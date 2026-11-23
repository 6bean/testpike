package com.sixbean.testpike.core.behavior.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeAdvisory;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeEvent;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorOutcomeStat;
import com.sixbean.testpike.core.behavior.domain.service.BehaviorOutcomeService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * Outcome ledger implementation (P7 baseline: NamedParameterJdbcTemplate).
 * Idempotency rides on the (source, source_event_id) unique key with
 * ON CONFLICT DO NOTHING, so a replayed verdict neither inserts a row nor
 * nudges any aggregate. The result rate is weighted: adopted scores 1,
 * corrected and partial score 0.5 (material that was useful but needed
 * human finishing earns half credit), deprecated scores 0 — the choice is
 * deliberate so a version that only produces half-right output is visibly
 * worse than one people accept as-is, yet not condemned as useless.
 * The comparison step re-reads the tolerance key every call (hot reload);
 * out-of-range or unparsable values fall back to the shipped default so a
 * typo on the settings page can never silence the advisory.
 */
@Service
public class BehaviorOutcomeServiceImpl implements BehaviorOutcomeService {

    /** K8 tolerance in percentage points before a degradation raises the advisory
     *  (shared with the rollout monitor so threshold semantics stay single-sourced). */
    public static final String KEY_AB_THRESHOLD = "ai.behavior.ab-roll-back-threshold";
    /** Ledger source tag for derived adoption forms : human handling of an
 * AI-produced case observed from the before/after text pair, never a click. */
    public static final String SOURCE_ADOPTION = "adoption";
    public static final int SHIPPED_DEFAULT_PP = 10;
    static final int MIN_PP = 1;
    static final int MAX_PP = 50;

    private static final Set<String> SCOPES = Set.of("session", "document", "task");
    private static final Set<String> OUTCOMES = Set.of("adopted", "deprecated", "corrected", "partial");
    private static final Set<String> SOURCES = Set.of("review", "chip", "draft", SOURCE_ADOPTION);

    private final NamedParameterJdbcTemplate named;
    private final ConfigService configService;

    public BehaviorOutcomeServiceImpl(NamedParameterJdbcTemplate named, ConfigService configService) {
        this.named = named;
        this.configService = configService;
    }

    /**
     * Runs on its own transaction: the ledger observes verdicts, it never
     * participates in them — a constraint rejection here must not mark the
     * caller's review transaction rollback-only (the intake channel must never
     * block review). A host rollback after the ledger committed may leave one
     * phantom row; accepted for an advisory-grade ledger.
     */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean record(BehaviorOutcomeEvent event) {
        if (event == null) {
            throw new ValidationException("behavior outcome event must not be null");
        }
        requireText(event.templateDomain(), "template domain");
        requireText(event.sourceEventId(), "source event id");
        requireAllowed(event.scope(), SCOPES, "scope");
        requireAllowed(event.outcome(), OUTCOMES, "outcome");
        requireAllowed(event.source(), SOURCES, "source");
        BigDecimal weight = event.weight() == null ? BigDecimal.ONE : event.weight();
        if (weight.signum() <= 0 || weight.compareTo(new BigDecimal("10")) > 0) {
            throw new ValidationException(
                    "behavior outcome weight must be greater than 0 and at most 10, got " + weight.toPlainString());
        }
        int inserted = named.update("""
                INSERT INTO tp_ai_behavior_outcome
                    (template_domain, template_version_id, scope, scope_ref,
                     source, source_event_id, outcome, weight, project_id)
                VALUES (:domain, :versionId, :scope, :scopeRef, :source, :eventId, :outcome, :weight, :projectId)
                ON CONFLICT (source, source_event_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("domain", event.templateDomain().trim())
                        .addValue("versionId", event.templateVersionId())
                        .addValue("scope", event.scope())
                        .addValue("scopeRef", event.scopeRef())
                        .addValue("source", event.source())
                        .addValue("eventId", event.sourceEventId().trim())
                        .addValue("outcome", event.outcome())
                        .addValue("weight", weight)
                        .addValue("projectId", event.projectId()));
        return inserted == 1;
    }

    @Override
    public List<BehaviorOutcomeStat> statsByTemplateVersion(String templateDomain) {
        requireText(templateDomain, "template domain");
        return named.query("""
                SELECT template_version_id,
                       COUNT(*) AS event_count,
                       SUM(weight * CASE outcome
                           WHEN 'adopted' THEN 1.0
                           WHEN 'corrected' THEN 0.5
                           WHEN 'partial' THEN 0.5
                           ELSE 0 END) / SUM(weight) AS result_rate
                FROM tp_ai_behavior_outcome
                WHERE template_domain = :domain AND template_version_id IS NOT NULL
                GROUP BY template_version_id
                ORDER BY template_version_id
                """,
                new MapSqlParameterSource("domain", templateDomain.trim()),
                (rs, rowNum) -> {
                    BigDecimal rate = rs.getBigDecimal("result_rate");
                    return new BehaviorOutcomeStat(rs.getLong("template_version_id"),
                            rs.getLong("event_count"),
                            rate == null ? null : rate.setScale(4, RoundingMode.HALF_UP));
                });
    }

    @Override
    public BehaviorOutcomeAdvisory compareVersions(String templateDomain, long controlVersionId, long candidateVersionId) {
        requireText(templateDomain, "template domain");
        int tolerance = parseThreshold(configService.getValue(KEY_AB_THRESHOLD, String.valueOf(SHIPPED_DEFAULT_PP)));
        BehaviorOutcomeStat control = rateOf(templateDomain.trim(), controlVersionId);
        BehaviorOutcomeStat candidate = rateOf(templateDomain.trim(), candidateVersionId);
        String domain = templateDomain.trim();
        if (control == null || candidate == null) {
            return new BehaviorOutcomeAdvisory(domain, controlVersionId, candidateVersionId,
                    control == null ? null : control.resultRate(),
                    candidate == null ? null : candidate.resultRate(),
                    null, tolerance, false,
                    "Comparison skipped: version " + (control == null ? controlVersionId : candidateVersionId)
                            + " of domain '" + domain + "' has no outcome rows yet — an advisory needs data on both sides.");
        }
        BigDecimal drop = control.resultRate().subtract(candidate.resultRate())
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        boolean advise = drop.compareTo(new BigDecimal(tolerance)) > 0;
        String note = advise
                ? "Candidate version " + candidateVersionId + " of domain '" + domain + "' trails control "
                        + controlVersionId + " by " + drop.toPlainString() + "pp, beyond the " + tolerance
                        + "pp tolerance — a rollback of the gray release is worth a human look (nothing changes by itself)."
                : "Candidate version " + candidateVersionId + " trails control " + controlVersionId
                        + " by " + drop.toPlainString() + "pp, inside the " + tolerance + "pp tolerance — keep observing.";
        return new BehaviorOutcomeAdvisory(domain, controlVersionId, candidateVersionId,
                control.resultRate(), candidate.resultRate(), drop, tolerance, advise, note);
    }

    private BehaviorOutcomeStat rateOf(String domain, long versionId) {
        List<BehaviorOutcomeStat> rows = named.query("""
                SELECT :versionId AS template_version_id,
                       COUNT(*) AS event_count,
                       SUM(weight * CASE outcome
                           WHEN 'adopted' THEN 1.0
                           WHEN 'corrected' THEN 0.5
                           WHEN 'partial' THEN 0.5
                           ELSE 0 END) / NULLIF(SUM(weight), 0) AS result_rate
                FROM tp_ai_behavior_outcome
                WHERE template_domain = :domain AND template_version_id = :versionId
                """,
                new MapSqlParameterSource()
                        .addValue("domain", domain)
                        .addValue("versionId", versionId),
                (rs, rowNum) -> {
                    long count = rs.getLong("event_count");
                    if (count == 0) {
                        return null;
                    }
                    BigDecimal rate = rs.getBigDecimal("result_rate").setScale(4, RoundingMode.HALF_UP);
                    return new BehaviorOutcomeStat(versionId, count, rate);
                });
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Tolerance parsing shared by evaluation, self-check and the rollout
     * monitor: a value outside the declared range or unparsable text falls
     * back to the shipped 10pp, never to silence.
     */
    public static int parseThreshold(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value < MIN_PP || value > MAX_PP ? SHIPPED_DEFAULT_PP : value;
        } catch (RuntimeException notUsable) {
            return SHIPPED_DEFAULT_PP;
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("behavior outcome " + field + " must not be blank");
        }
    }

    private static void requireAllowed(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new ValidationException("behavior outcome " + field + " '" + value
                    + "' is not one of " + allowed.stream().sorted().toList());
        }
    }
}

package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPair;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftStat;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftVerdict;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Golden-set drift ledger + promotion gate ( second round, item 4, the
 * pre-release twin of the first-round outcome loop). A replay of the domain's
 * golden pack through the control and the candidate template version of one
 * model produces a verdict; a verdict whose drift rate exceeds
 * ai.behavior.drift-block-threshold (shipped 15pp, hot-reloaded per call)
 * blocks promoting that candidate to full release, and NO verdict blocks too:
 * an untested version has no admission ticket. Fail-closed is deliberate —
 * the gate only ever guards the automatic promotion path; manual rollback and
 * human-named operations keep flowing (red line: status changes stay human).
 * Verdict history is append-only so every admission decision stays auditable.
 */
@Component
public class GoldenDriftGate {

    /** Blocking bar in percentage points (K8, hot-reloaded, range-guarded). */
    public static final String KEY_THRESHOLD = "ai.behavior.drift-block-threshold";
    /** Factory default from the adjudicated round-two item 4. */
    public static final int SHIPPED_DEFAULT_PP = 15;
    /** Config values outside this range fall back to the shipped default. */
    static final int MIN_PP = 1;
    static final int MAX_PP = 50;

    /** Bounded ledger window (same worst-scale clamp as the journey listing). */
    static final int MAX_PAGE_SIZE = 200;

    /** Axis: two prompt-template versions of one model (the promotion gate reads only this). */
    public static final String KIND_TEMPLATE = "template";
    /** Axis: one template family replayed through two model-route rows . */
    public static final String KIND_ROUTE = "route";
    /** Axis: one template+model replayed through old vs new assembler head code. */
    public static final String KIND_HEAD = "head";
    private static final List<String> AXIS_KINDS = List.of(KIND_TEMPLATE, KIND_ROUTE, KIND_HEAD);

    /** Every ledger read shares this column list — a new column lands in one place. */
    private static final String ROW_COLUMNS = """
            id, template_domain, template_name, candidate_version_id, control_version_id,
            pack_name, pack_version, snapshot_digest, sample_total, drifted_count,
            drift_percent_pp, threshold_pp, pass_flag, note, created_at,
            comparison_kind, control_ref, candidate_ref""";

    private static final Logger log = LoggerFactory.getLogger(GoldenDriftGate.class);

    private final NamedParameterJdbcTemplate named;
    private final ConfigService configService;

    public GoldenDriftGate(NamedParameterJdbcTemplate named, ConfigService configService) {
        this.named = named;
        this.configService = configService;
    }

    /**
     * Records one golden replay comparison for a candidate template version.
     * The drift statistics are derived server-side from the pairs — a caller
     * cannot talk the ledger into a number it did not replay. Recording never
     * overwrites: the newest verdict governs, older rows stay for audit.
     *
     * @return true when the verdict landed (it always does for a valid comparison)
     * @throws IllegalArgumentException on malformed pairs or unknown template rows
     */
    public boolean recordComparison(String templateDomain, String templateName,
                                    long candidateVersionId, long controlVersionId,
                                    String packName, Integer packVersion, String snapshotDigest,
                                    List<GoldenDriftPair> pairs, String note) {
        return recordComparison(templateDomain, templateName, KIND_TEMPLATE, null, null,
                candidateVersionId, controlVersionId, packName, packVersion, snapshotDigest, pairs, note);
    }

    /** Non-template axes shape: the compared subjects are the refs, not template rows. */
    public boolean recordComparison(String templateDomain, String templateName, String comparisonKind,
                                    String controlRef, String candidateRef,
                                    String packName, Integer packVersion, String snapshotDigest,
                                    List<GoldenDriftPair> pairs, String note) {
        return recordComparison(templateDomain, templateName, comparisonKind, controlRef, candidateRef,
                0L, 0L, packName, packVersion, snapshotDigest, pairs, note);
    }

    /**
 * Axis-aware recording . kind=route replays one template family
 * through two model-route rows; kind=head replays it through the old and the
 * new assembler head (gear changes of the wiring code, not of the template
 * text). The subjects are named on the ref pair and the template id columns
 * record 0 — an FK to a row that does not exist is never claimed. Neither
 * axis feeds the promotion gate: template-axis semantics stay byte-for-byte
 * the shape (regression-pinned by the ledger test).
 */
    public boolean recordComparison(String templateDomain, String templateName, String comparisonKind,
                                    String controlRef, String candidateRef,
                                    long candidateVersionId, long controlVersionId,
                                    String packName, Integer packVersion, String snapshotDigest,
                                    List<GoldenDriftPair> pairs, String note) {
        String domain = requireText(templateDomain, "template domain");
        String name = requireText(templateName, "template name");
        requireText(packName, "golden pack name");
        String kind = normalizeKind(comparisonKind);
        long candidate = candidateVersionId;
        long control = controlVersionId;
        String controlSide = controlRef;
        String candidateSide = candidateRef;
        if (KIND_TEMPLATE.equals(kind)) {
            if (candidate <= 0 || control <= 0) {
                throw new IllegalArgumentException("candidate and control template ids must be positive");
            }
            if (candidate == control) {
                throw new IllegalArgumentException(
                        "candidate and control must be two different template versions (id=" + candidate + ")");
            }
            requireTemplateRow(candidate, "candidate");
            requireTemplateRow(control, "control");
        } else {
            candidate = 0;
            control = 0;
            controlSide = requireText(controlRef, kind + " control side");
            candidateSide = requireText(candidateRef, kind + " candidate side");
            if (controlSide.equals(candidateSide)) {
                throw new IllegalArgumentException(
                        "candidate and control must be two different " + kind + " subjects (" + candidateSide + ")");
            }
        }
        GoldenDriftStat stat = GoldenSetDriftStatistics.analyze(pairs);
        int threshold = currentThresholdPp();
        boolean passed = stat.driftPercentPp().compareTo(new BigDecimal(threshold)) <= 0;
        named.update("""
                INSERT INTO tp_ai_golden_drift_verdict
                    (template_domain, template_name, candidate_version_id, control_version_id,
                     pack_name, pack_version, snapshot_digest, sample_total, drifted_count,
                     drift_percent_pp, threshold_pp, pass_flag, note,
                     comparison_kind, control_ref, candidate_ref)
                VALUES (:domain, :name, :candidate, :control,
                        :packName, :packVersion, :digest, :total, :drifted,
                        :rate, :threshold, :pass, :note,
                        :kind, :controlRef, :candidateRef)
                """,
                new MapSqlParameterSource()
                        .addValue("domain", domain).addValue("name", name)
                        .addValue("candidate", candidate).addValue("control", control)
                        .addValue("packName", packName.trim()).addValue("packVersion", packVersion)
                        .addValue("digest", normalizeDigest(snapshotDigest))
                        .addValue("total", stat.sampleTotal()).addValue("drifted", stat.driftedCount())
                        .addValue("rate", stat.driftPercentPp())
                        .addValue("threshold", threshold).addValue("pass", passed ? 1 : 0)
                        .addValue("note", note == null ? null : note.trim())
                        .addValue("kind", kind)
                        .addValue("controlRef", KIND_TEMPLATE.equals(kind) ? null : controlSide.trim())
                        .addValue("candidateRef", KIND_TEMPLATE.equals(kind) ? null : candidateSide.trim()));
        log.info("golden drift verdict kind={} domain={} name={} candidate={} ref={} drift={}pp threshold={}pp pass={}",
                kind, domain, name, candidate, candidateSide,
                stat.driftPercentPp().toPlainString(), threshold, passed);
        return true;
    }

    /** Newest template-axis verdict for one candidate version, or null when never replayed. */
    public GoldenDriftVerdict latestVerdict(String templateDomain, long candidateVersionId) {
        if (templateDomain == null || templateDomain.isBlank() || candidateVersionId <= 0) {
            return null;
        }
        return newestRow("""
                SELECT %s FROM tp_ai_golden_drift_verdict
                WHERE template_domain = :domain AND candidate_version_id = :candidate
                  AND comparison_kind = 'template'
                ORDER BY id DESC LIMIT 1
                """.formatted(ROW_COLUMNS),
                new MapSqlParameterSource().addValue("domain", templateDomain.trim())
                        .addValue("candidate", candidateVersionId));
    }

    /** Newest verdict on one non-template axis for the named subject pair. */
    public GoldenDriftVerdict latestVerdictAxis(String templateDomain, String templateName,
                                                String comparisonKind, String controlRef, String candidateRef) {
        String kind = normalizeKind(comparisonKind);
        if (KIND_TEMPLATE.equals(kind) || templateDomain == null || templateDomain.isBlank()) {
            return null;
        }
        return newestRow("""
                SELECT %s FROM tp_ai_golden_drift_verdict
                WHERE template_domain = :domain AND template_name = :name
                  AND comparison_kind = :kind AND control_ref = :controlRef AND candidate_ref = :candidateRef
                ORDER BY id DESC LIMIT 1
                """.formatted(ROW_COLUMNS),
                new MapSqlParameterSource().addValue("domain", templateDomain.trim())
                        .addValue("name", requireText(templateName, "template name"))
                        .addValue("kind", kind)
                        .addValue("controlRef", requireText(controlRef, kind + " control side"))
                        .addValue("candidateRef", requireText(candidateRef, kind + " candidate side")));
    }

    /**
 * Paged route-axis ledger read : every replay of
 * a model-route switch, newest-first, with the optional domain / control /
 * candidate filters the dashboard's one-click takeover queries use. The
 * read is pinned to kind='route' so template and head verdicts never leak
 * in; the oversized page size is clamped to the bounded window instead of a
 * silent full-table scan (S-group worst-scale contract).
 */
    public GoldenDriftPageView routeVerdictsPage(String domain, String controlRef,
                                                 String candidateRef, int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("route verdict page must be >= 1 (got " + page + ")");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("route verdict page size must be positive (got " + pageSize + ")");
        }
        int capped = Math.min(pageSize, MAX_PAGE_SIZE);
        StringBuilder where = new StringBuilder("WHERE comparison_kind = 'route'");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (domain != null && !domain.isBlank()) {
            where.append(" AND template_domain = :domain");
            params.addValue("domain", domain.trim());
        }
        if (controlRef != null && !controlRef.isBlank()) {
            where.append(" AND control_ref = :controlRef");
            params.addValue("controlRef", controlRef.trim());
        }
        if (candidateRef != null && !candidateRef.isBlank()) {
            where.append(" AND candidate_ref = :candidateRef");
            params.addValue("candidateRef", candidateRef.trim());
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_ai_golden_drift_verdict " + where, params, Long.class);
        params.addValue("limit", capped).addValue("offset", (long) (page - 1) * capped);
        List<GoldenDriftVerdict> rows = named.query(
                ("SELECT %s FROM tp_ai_golden_drift_verdict " + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset")
                        .formatted(ROW_COLUMNS),
                params, new GoldenDriftVerdictRowMapper());
        return new GoldenDriftPageView(rows, total == null ? 0L : total, page, capped);
    }

    /**
 * Admission check the D4 promotion path must pass .
 * Refusals are ValidationExceptions that name what is missing or failed,
 * the measured numbers, and the hot-reload key — an operator should never
 * have to read code to clear this gate.
 */
    public void requirePromotionAdmission(Long templateId, String domain, String name) {
        GoldenDriftVerdict verdict = latestVerdict(domain, templateId == null ? -1L : templateId);
        if (verdict == null) {
            throw new ValidationException("模板版本「" + name + "」还没有黄金回放对照结论，晋升全量被拦：先在功能页面完成该业务域"
                    + "黄金样本包的同模型回放并登记结论（判定键 " + KEY_THRESHOLD + "，修改即时生效无需重启）。");
        }
        if (!verdict.passed()) {
            throw new ValidationException("模板版本「" + name + "」黄金回放漂移率 "
                    + verdict.driftPercentPp().toPlainString() + "% 超过拦截阈值 " + verdict.thresholdPp()
                    + " 个百分点，晋升全量被拦：可继续灰度观察或回滚，也可在系统设置调整 " + KEY_THRESHOLD
                    + "（当前出厂缺省 " + SHIPPED_DEFAULT_PP + "，修改即时生效）。");
        }
    }

    /** Threshold in force right now (K8 hot read; out-of-range or garbage falls back to shipped default). */
    public int currentThresholdPp() {
        return parseThreshold(configService.getValue(KEY_THRESHOLD, String.valueOf(SHIPPED_DEFAULT_PP)));
    }

    /** Same range-guard shape as the first-round A/B tolerance key (BehaviorOutcomeServiceImpl). */
    static int parseThreshold(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value < MIN_PP || value > MAX_PP ? SHIPPED_DEFAULT_PP : value;
        } catch (RuntimeException fallback) {
            return SHIPPED_DEFAULT_PP;
        }
    }

    private void requireTemplateRow(long id, String label) {
        Integer found = named.queryForObject(
                "SELECT count(*) FROM tp_prompt_template WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        if (found == null || found == 0) {
            throw new IllegalArgumentException(label + " template version does not exist (id=" + id + ")");
        }
    }

    /** Axis names are exact (lowercase constants); null/blank reads as the template axis. */
    private static String normalizeKind(String comparisonKind) {
        if (comparisonKind == null || comparisonKind.isBlank()) {
            return KIND_TEMPLATE;
        }
        String kind = comparisonKind.trim();
        if (!AXIS_KINDS.contains(kind)) {
            throw new IllegalArgumentException("comparison kind must be one of " + AXIS_KINDS + " (got " + kind + ")");
        }
        return kind;
    }

    /** Shared single-row ledger read — newest-first LIMIT 1 shape used by both read paths. */
    private GoldenDriftVerdict newestRow(String sql, MapSqlParameterSource params) {
        List<GoldenDriftVerdict> rows = named.query(sql, params, new GoldenDriftVerdictRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String normalizeDigest(String digest) {
        String trimmed = digest == null ? "" : digest.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.length() != 64 || !trimmed.chars().allMatch(c -> "0123456789abcdef".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("golden pack snapshot_digest must be a 64-char sha-256 hex");
        }
        return trimmed;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}

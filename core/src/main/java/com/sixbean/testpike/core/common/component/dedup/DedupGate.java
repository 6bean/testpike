package com.sixbean.testpike.core.common.component.dedup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DedupGate: the single-source three-tier duplicate gate every create-type
 * draft must pass before a new row lands (design decision A7).
 *
 * <p>Tier L1 - deterministic key: project x entity type x normalized title
 * (trim, whitespace collapse, NFKC full-width/half-width folding, case fold).
 * A match refuses the create and names the existing id; the draft re-points
 * to an anchored update upstream of this gate.
 * <p>Tier L2 - fuzzy: pg_trgm similarity over the titles of the same project.
 * Score at or above the high line behaves like L1; scores in the mid band
 * return the top-N near neighbours and leave a near-miss audit trail;
 * everything below the mid line passes quietly. Exception at the high line:
 * candidates whose digit sequence differs from the probe's (numbered
 * enumerations such as item "-2-" vs "-25-") are never refused - different
 * numbers are different items - they stay visible in the mid band instead.
 * <p>TOCTOU guard: {@link #recheckRequirementL1} runs the deterministic key
 * again at write instant, closing the window between card time and commit.
 *
 * <p>Hard boundaries (non-negotiable, restated wherever this gate is wired):
 * <ol>
 *   <li>Never merge candidates across entity types - the idempotency domain
 *       is per entity type (requirement / case / API contract / mock).</li>
 *   <li>The gate only flags suspected duplicates; it must NEVER rewrite
 *       user content on its own.</li>
 *   <li>A human overriding the gate (choosing "definitely a new item") is
 *       respected, and the decision keeps its audit trail. Update drafts
 *       still pass the regular one-click confirmation door.</li>
 * </ol>
 *
 * <p>Entity business-key registry (each entity wires its own key at its
 * access point; a missing registration means that entity's gate is nominal):
 * requirement = project + normalized title; case = case_no;
 * API contract = normalized method + path; mock = space + method + match
 * (all three wired at their create points by this batch).
 *
 * <p>Thresholds are hot-reload config keys, never hardcoded call-site values:
 * {@code ai.dedup.enabled} (factory default on), {@code ai.dedup.l2-high-score},
 * {@code ai.dedup.l2-mid-score}, {@code ai.dedup.candidate-top-n}.
 * The L1 scan runs at application level over the project's live titles by
 * design: no database unique constraint on requirement titles, renames and
 * cross-chapter homonyms stay legal.
 */
@Component
public class DedupGate {

    static final String KEY_ENABLED = "ai.dedup.enabled";
    static final String KEY_HIGH = "ai.dedup.l2-high-score";
    static final String KEY_MID = "ai.dedup.l2-mid-score";
    static final String KEY_TOP_N = "ai.dedup.candidate-top-n";
    static final double DEFAULT_HIGH = 0.85;
    static final double DEFAULT_MID = 0.60;
    static final int DEFAULT_TOP_N = 5;
    static final double MIN_SCORE = 0.05;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 20;

    private static final ObjectMapper MATCHER_MAPPER = new ObjectMapper();
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

    private final NamedParameterJdbcTemplate jdbc;
    private final SystemConfigReader configReader;
    private final AuditLogService auditLogService;

    public DedupGate(NamedParameterJdbcTemplate jdbc,
                     SystemConfigReader configReader,
                     AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.auditLogService = auditLogService;
    }

    /**
     * Normalized-title single source: NFKC folding (full-width to half-width
     * punctuation and letters, ideographic space to plain space), whitespace
     * run collapse, trim, case fold. Regression target: the legacy extraction
     * merge compared raw titles, so one added space or one full-width variant
     * defeated it entirely.
     */
    public static String normalizeTitle(String raw) {
        // the fold itself lives in the merge single source
        // (common TitleIdempotentMerge) shared with the extraction planes;
        // this public entry keeps its callers and delegates byte-identically.
        return com.sixbean.testpike.common.util.TitleIdempotentMerge.normalizeTitle(raw);
    }

    /** Evaluates the three tiers for a requirement create probe within one project. */
    public DedupVerdict evaluateRequirement(long projectId, String title) {
        String normalized = normalizeTitle(title);
        if (normalized.isEmpty() || !configReader.getBoolean(KEY_ENABLED, true)) {
            return DedupVerdict.pass(normalized);
        }
        List<DedupCandidate> exact = scanExactMatches(projectId, normalized);
        if (!exact.isEmpty()) {
            return DedupVerdict.exact(normalized, exact.get(0));
        }
        double high = readScore(KEY_HIGH, DEFAULT_HIGH);
        double mid = readScore(KEY_MID, DEFAULT_MID);
        if (mid >= high) {
            // Inverted band pair is unusable; keep high and pull mid under it.
            mid = Math.max(MIN_SCORE, Math.min(DEFAULT_MID, high - 0.01));
        }
        int topN = clampTopN();
        List<DedupCandidate> near = scanSimilarCandidates(projectId, title, mid, topN);
        if (near.isEmpty()) {
            return DedupVerdict.pass(normalized);
        }
        // Digit-divergence exception at the high line: a numbered enumeration
        // ("...-2-..." then "...-25-...") differs only in its digit sequence,
        // yet trigrams score such pairs above 0.85 because the shared CJK
        // frame dominates. Different numbers are different items - refusal
        // would block a legitimate create. The pair stays visible through the
        // near-miss audit below (medium band), so nothing is silently dropped.
        List<String> probeRuns = digitRuns(normalized);
        Optional<DedupCandidate> refusal = near.stream()
                .filter(c -> c.score() >= high && digitRuns(normalizeTitle(c.title())).equals(probeRuns))
                .findFirst();
        if (refusal.isPresent()) {
            return DedupVerdict.high(normalized, refusal.get());
        }
        writeNearMissAudit(projectId, title, normalized, near);
        return DedupVerdict.medium(normalized, near);
    }

    /**
     * Ordered digit runs of an already-folded title - the numeric skeleton
     * that separates enumerated items while character trigrams blur them.
     * Two titles may only refuse each other at the fuzzy high line when this
     * skeleton is identical.
     */
    static List<String> digitRuns(String folded) {
        List<String> runs = new ArrayList<>();
        Matcher m = DIGIT_RUN.matcher(folded);
        while (m.find()) {
            runs.add(m.group());
        }
        return runs;
    }

    /**
     * TOCTOU guard for the requirement write path: deterministic L1 key only,
     * re-run at write instant. Returns the existing row that now carries this
     * normalized title, if any. Honours the master switch so an operator who
     * turned the gate off is never re-blocked at commit time.
     */
    public Optional<DedupCandidate> recheckRequirementL1(long projectId, String title) {
        String normalized = normalizeTitle(title);
        if (normalized.isEmpty() || !configReader.getBoolean(KEY_ENABLED, true)) {
            return Optional.empty();
        }
        List<DedupCandidate> exact = scanExactMatches(projectId, normalized);
        return exact.isEmpty() ? Optional.empty() : Optional.of(exact.get(0));
    }

    /**
     * Business-key normalization single source: NFKC fold + whitespace
     * collapse + case fold + uppercase. Case_no is stored upper-case, so a
     * lower-case or padded probe (the AI draft / import shape) must fold to
     * the same key instead of slipping past the raw compare.
     */
    public static String normalizeBusinessKey(String raw) {
        return normalizeTitle(raw).toUpperCase(Locale.ROOT);
    }

    /**
     * Endpoint key single source: method + normalized path, query string
     * dropped and trailing slash trimmed (same fold rule as the version-diff
     * endpointKey in the spec parse chain). Shared by the gate scan and every
     * create-point caller so the two sides can never drift.
     */
    public static String normalizeEndpointPath(String path) {
        if (path == null) {
            return "";
        }
        String cleaned = Normalizer.normalize(path, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
        int q = cleaned.indexOf('?');
        if (q >= 0) {
            cleaned = cleaned.substring(0, q);
        }
        if (cleaned.length() > 1 && cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    public static String endpointKey(String method, String path) {
        return normalizeBusinessKey(method) + " " + normalizeEndpointPath(path);
    }

    /**
     * Mock rule key = space + method + match (path pattern + matcher payload).
     * The payload is canonicalized (member names sorted, array element order
     * stable-sorted by canonical form) so a reordered matcher list folds to
     * the same key. Unparseable payloads fall back to the trimmed raw text —
     * conservative, never a false merge.
     */
    public static String stubKey(String method, String pathPattern, String matchersJson) {
        return normalizeBusinessKey(method) + " " + normalizeEndpointPath(pathPattern)
                + " " + canonicalizeMatchers(matchersJson);
    }

    private static String canonicalizeMatchers(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        try {
            JsonNode node = MATCHER_MAPPER.readTree(raw);
            return canonicalNode(node);
        } catch (Exception e) {
            return raw.trim();
        }
    }

    private static String canonicalNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<String> parts = new ArrayList<>();
            node.fields().forEachRemaining(e ->
                    parts.add(e.getKey() + ":" + canonicalNode(e.getValue())));
            Collections.sort(parts);
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(item -> parts.add(canonicalNode(item)));
            Collections.sort(parts);
            return "[" + String.join(",", parts) + "]";
        }
        return node.asText();
    }

    /**
     * L1 business-key verdict for a case create probe. case_no is globally
     * unique (DB constraint), so the scan is table-wide; the gate adds the
     * normalization fold the raw constraint cannot see (padding, full-width
     * or case variants of the same number). EXACT blocks; PASS carries the
     * scanned row in {@code matched} when one exists so the wiring side can
     * re-run this method at write instant and name the holder on a 409.
     */
    public DedupVerdict evaluateCase(long projectId, String caseNo) {
        String key = normalizeBusinessKey(caseNo);
        if (key.isEmpty() || !configReader.getBoolean(KEY_ENABLED, true)) {
            return DedupVerdict.pass(key);
        }
        List<DedupCandidate> exact = jdbc.query(
                "SELECT id, case_no FROM tp_test_case WHERE deleted = 0",
                new MapSqlParameterSource(),
                (rs, i) -> new DedupCandidate(rs.getLong("id"), rs.getString("case_no"), 1.0))
                .stream()
                .filter(c -> normalizeBusinessKey(c.title()).equals(key))
                .toList();
        if (exact.isEmpty()) {
            return DedupVerdict.pass(key);
        }
        return DedupVerdict.exact(key, exact.get(0));
    }

    /**
     * Write-instant companion of {@link #evaluateCase}: returns the live row
     * currently holding this normalized case_no, ignoring the gate switch —
     * the DB uniqueness this check mirrors is not switchable. The create path
     * uses it to name the current holder (renamed or created in the race
     * window) instead of a generic conflict.
     */
    public Optional<DedupCandidate> recheckCaseKeyAtWrite(String caseNo) {
        String key = normalizeBusinessKey(caseNo);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        List<DedupCandidate> exact = jdbc.query(
                "SELECT id, case_no FROM tp_test_case WHERE deleted = 0",
                new MapSqlParameterSource(),
                (rs, i) -> new DedupCandidate(rs.getLong("id"), rs.getString("case_no"), 1.0))
                .stream()
                .filter(c -> normalizeBusinessKey(c.title()).equals(key))
                .toList();
        return exact.isEmpty() ? Optional.empty() : Optional.of(exact.get(0));
    }

    /**
     * L1 endpoint-key verdict for an API contract create probe: does the
     * project already define this method + path inside some stored spec?
     * {@code sameSpecName} (nullable) marks the version-chain posture —
     * re-uploading the contract that already owns the endpoint is a new
     * version, not a duplicate definition, so rows under that exact name
     * are the only allowed owners.
     */
    public DedupVerdict evaluateApiEndpoint(long projectId, String method, String path) {
        return evaluateApiEndpoint(projectId, method, path, null);
    }

    public DedupVerdict evaluateApiEndpoint(long projectId, String method, String path,
                                            String sameSpecName) {
        String key = endpointKey(method, path);
        if (normalizeBusinessKey(method).isEmpty() || normalizeEndpointPath(path).isEmpty()
                || !configReader.getBoolean(KEY_ENABLED, true)) {
            return DedupVerdict.pass(key);
        }
        Optional<DedupCandidate> foreignOwner = firstEndpointOwner(projectId, key, sameSpecName);
        return foreignOwner
                .map(owner -> DedupVerdict.exact(key, owner))
                .orElseGet(() -> DedupVerdict.pass(key));
    }

    /**
     * The single endpoint-owner scan: rows whose parsed endpoint payload
     * carries this key, optionally excluding the version-chain owner name.
     * Shared by the duplicate check and the exemption probe so the two
     * questions never grow separate SQL (R40).
     */
    private Optional<DedupCandidate> firstEndpointOwner(long projectId, String key, String excludeName) {
        String normalizedName = excludeName == null ? "" : normalizeTitle(excludeName);
        return jdbc.query(
                        "SELECT s.id, s.name, e->>'method' AS m, e->>'path' AS p"
                                + " FROM tp_api_spec s, jsonb_array_elements(s.endpoints) AS e"
                                + " WHERE s.project_id = :pid",
                        new MapSqlParameterSource("pid", projectId),
                        (rs, i) -> new Object[] {rs.getLong("id"), rs.getString("name"),
                                endpointKey(rs.getString("m"), rs.getString("p"))})
                .stream()
                .filter(row -> row[2].equals(key))
                .filter(row -> normalizedName.isEmpty()
                        || !normalizeTitle((String) row[1]).equals(normalizedName))
                .map(row -> new DedupCandidate((Long) row[0], (String) row[1], 1.0))
                .findFirst();
    }

    /**
     * L1 business-key verdict for a mock rule create probe inside one space:
     * same method + path pattern + canonical matcher payload = the very same
     * routing rule. Rules never cross spaces; different payloads are
     * legitimate sibling rules.
     */
    public DedupVerdict evaluateMockStub(long spaceId, String method, String pathPattern,
                                         String matchersJson) {
        String key = stubKey(method, pathPattern, matchersJson);
        if (!configReader.getBoolean(KEY_ENABLED, true)) {
            return DedupVerdict.pass(key);
        }
        List<DedupCandidate> exact = jdbc.query(
                        "SELECT id, name, method, path_pattern, matchers_json::text AS matchers"
                                + " FROM tp_mock_stub WHERE space_id = :sid AND enabled = TRUE",
                        new MapSqlParameterSource("sid", spaceId),
                        (rs, i) -> new DedupCandidate(rs.getLong("id"),
                                stubKey(rs.getString("method"), rs.getString("path_pattern"),
                                        rs.getString("matchers")),
                                1.0))
                .stream()
                .filter(c -> c.title().equals(key))
                .toList();
        return exact.isEmpty() ? DedupVerdict.pass(key) : DedupVerdict.exact(key, exact.get(0));
    }

    /** Rule display name for a refusal message that must name the blocked row. */
    public String stubRuleName(long stubId) {
        List<String> names = jdbc.query(
                "SELECT name FROM tp_mock_stub WHERE id = :id",
                new MapSqlParameterSource("id", stubId),
                (rs, i) -> rs.getString("name"));
        return names.isEmpty() ? "?" : names.get(0);
    }

    private List<DedupCandidate> scanExactMatches(long projectId, String normalizedTitle) {
        return jdbc.query(
                        "SELECT id, title FROM tp_requirement WHERE project_id = :pid AND deleted = 0",
                        new MapSqlParameterSource("pid", projectId),
                        (rs, i) -> new DedupCandidate(rs.getLong("id"), rs.getString("title"), 1.0))
                .stream()
                .filter(c -> normalizeTitle(c.title()).equals(normalizedTitle))
                .toList();
    }

    private List<DedupCandidate> scanSimilarCandidates(long projectId, String title,
                                                       double mid, int topN) {
        return jdbc.query(
                "SELECT id, title, similarity(title, :probe) AS score FROM tp_requirement"
                        + " WHERE project_id = :pid AND deleted = 0"
                        + " AND similarity(title, :probe) >= :mid"
                        + " ORDER BY score DESC LIMIT :topn",
                new MapSqlParameterSource("pid", projectId)
                        .addValue("probe", normalizeTitle(title))
                        .addValue("mid", mid)
                        .addValue("topn", topN),
                (rs, i) -> new DedupCandidate(rs.getLong("id"), rs.getString("title"),
                        rs.getDouble("score")));
    }

    private void writeNearMissAudit(long projectId, String title, String normalizedTitle,
                                    List<DedupCandidate> candidates) {
        List<Map<String, Object>> shown = candidates.stream()
                .map(c -> Map.<String, Object>of("id", c.id(), "title", c.title(), "score", c.score()))
                .toList();
        auditLogService.record(new AuditLogInput(
                null, projectId, "requirement", String.valueOf(candidates.get(0).id()),
                "DEDUP_NEAR_MISS",
                Map.of("title", title, "normalizedTitle", normalizedTitle),
                Map.of("candidates", shown)));
    }

    /**
     * Reviewer-vouched override: the write path lands a row this gate blocks,
     * so the refusal is kept auditable with the blocked match named (same
     * posture as the near-miss audit above, single writer of the audit shape).
     */
    public void recordForceNewOverride(long projectId, String title, DedupCandidate matched,
                                       long operatorId) {
        auditLogService.record(new AuditLogInput(
                operatorId, projectId, "requirement", String.valueOf(matched.id()),
                "DEDUP_FORCE_NEW",
                Map.of("title", title, "normalizedTitle", normalizeTitle(title)),
                Map.of("blockedMatch", Map.of("id", matched.id(), "title", matched.title(),
                        "score", matched.score()))));
    }

    /** Hot score line parse with [0.05,1] clamp; missing/garbage falls back to the seeded default. */
    private double readScore(String key, double fallback) {
        try {
            double parsed = Double.parseDouble(configReader.getString(key, ""));
            return parsed < MIN_SCORE || parsed > 1.0 ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clampTopN() {
        int topN = configReader.getInt(KEY_TOP_N, DEFAULT_TOP_N);
        return topN < MIN_TOP_N || topN > MAX_TOP_N ? DEFAULT_TOP_N : topN;
    }
}

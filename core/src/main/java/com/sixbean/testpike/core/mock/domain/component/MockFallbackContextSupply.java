package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drafting-context supply for the A-3 fallback generation leg .
 * <p>The A-3 feature spec pins three contexts the instant answer must be
 * built on;
 * this component owns the two deterministic ones the core control plane can
 * read without spending anything:
 * <ul>
 * <li>{@code contractSchema} — the endpoint's declared response schema pulled
 * from the project's newest contract snapshot (tp_api_spec endpoints JSONB,
 * the same parsed shape StubDraftMapper consumes on the drafting side; per
 * contract name the highest spec_version wins, newest update first overall);</li>
 * <li>{@code fewShotSamples} — recorded request/response shapes of the same
 * endpoint family, read from the hit ledger. The ledger rows are already
 * masked (MockHitRecorder masks at record time, before anything is buffered —
 * the shared desensitization pipeline), and rows whose source is {@code llm}
 * are excluded: laundering a previously fabricated answer back into the
 * drafting context would let the fallback cite its own fiction as evidence.</li>
 * </ul>
 * <p>Supply-side honesty: absence is never a veto. No contract, no samples,
 * an unparsable snapshot — each degrades to null/empty and the caller still
 * escalates with the plain identity (the model then answers by its general
 * shape rules, the same way it did before this leg existed).
 */
@Component
public class MockFallbackContextSupply {

    /** K8 hot key: how many masked samples at most ride one escalation. */
    public static final String FEW_SHOT_MAX_KEY = "mock.llm-fallback.few-shot-max-samples";
    static final int DEFAULT_FEW_SHOT_MAX = 5;

    /** K8 hot key: how many lane exchanges at most form the session summary. */
    public static final String SESSION_SUMMARY_MAX_KEY = "mock.llm-fallback.session-summary-max";
    static final int DEFAULT_SESSION_SUMMARY_MAX = 8;

    /** Scan window when picking the family samples (newest ledger rows first). */
    private static final int FETCH_SCAN_LIMIT = 40;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SystemConfigReader configReader;

    public MockFallbackContextSupply(NamedParameterJdbcTemplate jdbc,
                                     ObjectMapper objectMapper,
                                     SystemConfigReader configReader) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.configReader = configReader;
    }

    /**
     * Declared response schemas of the endpoint as prompt-ready text
     * ({@code "<status> -> <schema json>"} lines, one per declared response),
     * or null when the project carries no contract covering this endpoint.
     *
     * @param normalizedPath request path as the data plane normalized it
     *                       (digit segments collapsed to the family marker)
     */
    public String contractSchemaJson(long projectId, String method, String normalizedPath) {
        List<String> endpointDocs = jdbc.query("""
                SELECT endpoints::text
                  FROM tp_api_spec
                 WHERE project_id = :projectId
                   AND (name, spec_version) IN (
                       SELECT name, MAX(spec_version) FROM tp_api_spec
                        WHERE project_id = :projectId GROUP BY name)
                 ORDER BY updated_at DESC
                """, new MapSqlParameterSource("projectId", projectId),
                (rs, n) -> rs.getString(1));
        for (String doc : endpointDocs) {
            String hit = firstEndpointSchema(doc, method, normalizedPath);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** Parse one endpoints snapshot and return the matching endpoint's schema text. */
    private String firstEndpointSchema(String endpointsJson, String method, String normalizedPath) {
        JsonNode root;
        try {
            root = objectMapper.readTree(endpointsJson);
        } catch (Exception e) {
            return null; // unparsable snapshot = no contract, never a broken answer leg
        }
        if (!root.isArray()) {
            return null;
        }
        for (JsonNode ep : root) {
            if (!method.equalsIgnoreCase(ep.path("method").asText(""))) {
                continue;
            }
            if (!pathFamilyMatches(ep.path("path").asText(""), normalizedPath)) {
                continue;
            }
            StringBuilder out = new StringBuilder();
            ep.path("responseSchemas").fields().forEachRemaining(entry -> {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(entry.getKey()).append(" -> ").append(entry.getValue().asText(""));
            });
            if (out.length() > 0) {
                return out.toString();
            }
        }
        return null;
    }

    /**
     * Family match: a contract segment {@code {var}} accepts anything, a
     * literal segment must equal. The data plane collapsed digit segments to
     * the {@code {n}} marker before the path arrived, so {@code /orders/7}
     * and {@code /orders/42} both find the contract's {@code /orders/{id}}.
     */
    static boolean pathFamilyMatches(String contractPath, String normalizedPath) {
        String a = trim(contractPath);
        String b = trim(normalizedPath);
        String[] as = a.split("/", -1);
        String[] bs = b.split("/", -1);
        if (as.length != bs.length) {
            return false;
        }
        for (int i = 0; i < as.length; i++) {
            boolean wildcard = as[i].startsWith("{") && as[i].endsWith("}");
            if (!wildcard && !as[i].equals(bs[i])) {
                return false;
            }
        }
        return true;
    }

    private static String trim(String path) {
        String p = path == null ? "/" : path.trim();
        return p.isEmpty() ? "/" : p;
    }

    /**
     * Masked ledger samples of the same endpoint family, newest first,
     * capped by the K8 key. An empty list whenever nothing recorded honestly
     * matches — the generation leg then drafts from contract/general shape
     * rules instead, same as before this leg existed.
     */
    public List<Map<String, Object>> fewShotSamples(long spaceId, String method,
                                                    String normalizedPath) {
        int max = Math.max(1, configReader.getInt(FEW_SHOT_MAX_KEY, DEFAULT_FEW_SHOT_MAX));
        List<SampleRow> rows = jdbc.query("""
                SELECT request_snapshot::text AS req, response_snapshot::text AS resp,
                       matched, source
                  FROM tp_mock_hit_log
                 WHERE space_id = :spaceId
                   AND matched = true
                   AND (source IS NULL OR source <> 'llm')
                 ORDER BY hit_at DESC
                 LIMIT :scan
                """, new MapSqlParameterSource().addValue("spaceId", spaceId)
                        .addValue("scan", FETCH_SCAN_LIMIT),
                (rs, n) -> new SampleRow(rs.getString("req"), rs.getString("resp")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SampleRow row : rows) {
            Map<String, Object> sample = toSample(row, method, normalizedPath);
            if (sample != null) {
                out.add(sample);
                if (out.size() == max) {
                    break;
                }
            }
        }
        return out;
    }

    /** One ledger pair into a prompt sample; shape trouble or family miss = null. */
    private Map<String, Object> toSample(SampleRow row, String method, String normalizedPath) {
        try {
            JsonNode req = objectMapper.readTree(row.requestJson());
            if (!method.equalsIgnoreCase(req.path("method").asText(""))) {
                return null;
            }
            if (!pathFamilyMatches(toFamily(req.path("path").asText("")), normalizedPath)) {
                return null;
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("method", req.path("method").asText("").toUpperCase(Locale.ROOT));
            sample.put("path", req.path("path").asText(""));
            if (!req.path("body").isMissingNode()) {
                sample.put("request", req.path("body").asText(""));
            }
            JsonNode resp = objectMapper.readTree(row.responseJson());
            if (!resp.path("status").isMissingNode()) {
                sample.put("status", resp.path("status").asInt());
            }
            if (!resp.path("body").isMissingNode()) {
                sample.put("response", resp.path("body").asText(""));
            }
            return sample;
        } catch (Exception e) {
            return null; // a broken row is skipped — the pipeline stays honest by omission
        }
    }

    /** Collapse digit segments to the family marker (data plane's normalization twin). */
    static String toFamily(String path) {
        String[] segments = (path == null ? "/" : path).split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(segments[i].matches("\\d+") ? "{n}" : segments[i]);
        }
        return out.toString();
    }

    /**
     * The lane's recent REAL exchanges as prompt-ready lines
     * ({@code "METHOD /path -> status"}, newest first, capped by the K8 key),
     * or null when no lane is carried or nothing honest was recorded.
     * <p>Same-source honesty as the few-shot leg: only rows that were
     * actually served ride the summary, and rows whose source is {@code llm}
     * are excluded — the fallback citing its own earlier fabrications as
     * "what already happened on this session" would launder fiction into
     * evidence. Ledger rows are masked at record time, so even the paths and
     * statuses here left the data plane through the desensitization pipeline.
     */
    public String sessionSummary(long spaceId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null; // no lane = no exchanges known — absence, never a veto
        }
        int max = Math.max(1, configReader.getInt(SESSION_SUMMARY_MAX_KEY,
                DEFAULT_SESSION_SUMMARY_MAX));
        List<ExchangeRow> rows = jdbc.query("""
                SELECT request_snapshot::text AS req, response_snapshot::text AS resp
                  FROM tp_mock_hit_log
                 WHERE space_id = :spaceId
                   AND session_id = :sessionId
                   AND (source IS NULL OR source <> 'llm')
                 ORDER BY hit_at DESC
                 LIMIT :max
                """, new MapSqlParameterSource().addValue("spaceId", spaceId)
                        .addValue("sessionId", sessionId)
                        .addValue("max", max),
                (rs, n) -> new ExchangeRow(rs.getString("req"), rs.getString("resp")));
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (ExchangeRow row : rows) {
            String line = toExchangeLine(row);
            if (line != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** One ledger pair into "METHOD /path -> status"; broken row = skipped, not fatal. */
    private String toExchangeLine(ExchangeRow row) {
        try {
            JsonNode req = objectMapper.readTree(row.requestJson());
            String method = req.path("method").asText("");
            String path = req.path("path").asText("");
            if (method.isBlank() || path.isBlank()) {
                return null;
            }
            JsonNode resp = objectMapper.readTree(row.responseJson());
            int status = resp.path("status").asInt(0);
            return method.toUpperCase(Locale.ROOT) + " " + path + " -> " + status;
        } catch (Exception e) {
            return null; // honesty by omission — a broken row states nothing
        }
    }

    /** One fetched ledger pair (record per layering rules — no inner class file needed). */
    private record SampleRow(String requestJson, String responseJson) {
    }

    /** One lane exchange read from the hit ledger (method/path + status sides). */
    private record ExchangeRow(String requestJson, String responseJson) {
    }
}

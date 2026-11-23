package com.sixbean.testpike.core.spec.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Contract response-schema lookup for the api-test 'schema' assertion
 * .
 * <p>Given a folded match key ("GET /orders/{var}" shape — the same key the
 * request table stores in contract_endpoint_key), returns the response schemas
 * of the newest contract snapshot that declares that endpoint, keyed by status
 * code, so the envelope assembler can pin them into the dispatch envelope
 * (contractSchemas key) without the executor ever reverse-querying the DB.
 * <p>Latest-wins semantics mirror the inventory anchor: per contract name the
 * highest spec_version row; across names the most recently updated snapshot is
 * consulted first. Absence is honest: no contract, no endpoint, or a snapshot
 * without response schemas = empty map (the executor then fails closed by name
 * when a contract-dialect row runs — never a silently-passing assertion).
 */
@Component
public class ContractResponseSchemaLookup {

    private final NamedParameterJdbcTemplate jdbc;
    private final ContractMatchKeyRules matchKeyRules;
    private final ObjectMapper objectMapper;

    public ContractResponseSchemaLookup(NamedParameterJdbcTemplate jdbc,
                                        ContractMatchKeyRules matchKeyRules,
                                        ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.matchKeyRules = matchKeyRules;
        this.objectMapper = objectMapper;
    }

    /**
     * Response schemas declared for one folded endpoint key in this project's
     * newest contract snapshots.
     *
     * @param foldedKey the request row's contract_endpoint_key (already folded);
     *                  null/blank = no lookup, empty result
     * @return status-code → schema-document map (insertion-ordered by code);
     *         empty when no contract covers the endpoint
     */
    public Map<String, String> schemasForKey(long projectId, String foldedKey) {
        Map<String, String> out = new LinkedHashMap<>();
        if (foldedKey == null || foldedKey.isBlank()) {
            return out;
        }
        int sp = foldedKey.indexOf(' ');
        if (sp <= 0 || sp >= foldedKey.length() - 1) {
            return out; // malformed key shape = honest miss, never a crash mid-dispatch
        }
        String wantMethod = foldedKey.substring(0, sp).toUpperCase(Locale.ROOT);
        String wantPath = foldedKey.substring(sp + 1);
        // Newest version per contract name, newest updates first (anchor shape
        // shared with the inventory view; SELECT kept column-light — only the
        // endpoints snapshot is read).
        var docs = jdbc.query("""
                SELECT endpoints::text
                  FROM tp_api_spec
                 WHERE project_id = :projectId
                   AND (name, spec_version) IN (
                       SELECT name, MAX(spec_version) FROM tp_api_spec
                        WHERE project_id = :projectId GROUP BY name)
                 ORDER BY updated_at DESC, id DESC
                """, new MapSqlParameterSource("projectId", projectId),
                (rs, n) -> rs.getString(1));
        for (String doc : docs) {
            collectFromSnapshot(doc, wantMethod, wantPath, out);
            if (!out.isEmpty()) {
                return out;
            }
        }
        return out;
    }

    /** First snapshot declaring the endpoint (folded-key equality) wins. */
    private void collectFromSnapshot(String endpointsJson, String wantMethod,
                                     String wantPath, Map<String, String> out) {
        JsonNode root;
        try {
            root = objectMapper.readTree(endpointsJson);
        } catch (Exception e) {
            return; // unparsable snapshot = skip this contract, never a broken dispatch
        }
        if (!root.isArray()) {
            return;
        }
        for (JsonNode ep : root) {
            if (!wantMethod.equalsIgnoreCase(ep.path("method").asText(""))) {
                continue;
            }
            // Fold the snapshot's own endpoint key with the shared rules so the
            // comparison sits in one dialect (template {id} vs folded {var}).
            String folded = matchKeyRules.matchKeyFromLoose(ep.path("endpointKey").asText(""));
            if (!folded.equals(wantMethod + " " + wantPath)) {
                continue;
            }
            JsonNode schemas = ep.path("responseSchemas");
            schemas.fields().forEachRemaining(entry -> {
                String schema = entry.getValue().asText("");
                if (!schema.isBlank()) {
                    out.put(entry.getKey(), schema);
                }
            });
            if (!out.isEmpty()) {
                return;
            }
        }
    }
}

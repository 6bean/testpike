package com.sixbean.testpike.core.linkage.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Folded endpoint-key view of one contract version row — the single source
 * shared by both contract fan-out bean detectors (request side and mock
 * side). Reading tp_api_spec.endpoints and folding every endpointKey through
 * ContractMatchKeyRules here means the two detectors compare against exactly
 * the same key form the import and derivation paths wrote, and a future
 * third consumer references this component instead of copying the SELECT and
 * the fold loop (R40: same logic twice is a defect).
 */
@Component
public class ContractSpecFoldedKeys {

    private static final String SELECT_SPEC =
            "SELECT project_id, endpoints::text AS endpoints FROM tp_api_spec WHERE id = :specId";

    private final NamedParameterJdbcTemplate named;
    private final ContractMatchKeyRules matchKeyRules;
    private final ObjectMapper objectMapper;

    public ContractSpecFoldedKeys(NamedParameterJdbcTemplate named,
                                  ContractMatchKeyRules matchKeyRules,
                                  ObjectMapper objectMapper) {
        this.named = named;
        this.matchKeyRules = matchKeyRules;
        this.objectMapper = objectMapper;
    }

    /** Owning project of the spec version plus its deduplicated folded keys. */
    public record SpecFanKeys(long projectId, List<String> foldedKeys) {
    }

    /**
     * @return keys of that spec version, or null when the row does not exist
     *         (a deleted or never-landed spec arms nothing, callers return an
     *         empty fan)
     */
    public SpecFanKeys keysOf(Long specId) {
        if (specId == null) {
            return null;
        }
        List<Map<String, Object>> rows = named.queryForList(SELECT_SPEC,
                new MapSqlParameterSource("specId", specId));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        long projectId = ((Number) row.get("project_id")).longValue();
        return new SpecFanKeys(projectId, foldKeys((String) row.get("endpoints")));
    }

    /**
     * Fan keys only when there is actually something to fan to: an absent,
     * deleted or endpoint-less spec version yields an empty Optional. Both
     * contract detectors share this single-sourced guard (R40) so the
     * "nothing to compare means nothing to arm" rule lives in one place
     * instead of a repeated null-or-empty check that can drift apart.
     */
    public Optional<SpecFanKeys> armedKeys(Long specId) {
        SpecFanKeys keys = keysOf(specId);
        return keys == null || keys.foldedKeys().isEmpty()
                ? Optional.empty()
                : Optional.of(keys);
    }

    private List<String> foldKeys(String endpointsJson) {
        if (endpointsJson == null || endpointsJson.isBlank()) {
            return List.of();
        }
        Set<String> folded = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(endpointsJson);
            if (root.isArray()) {
                for (JsonNode ep : root) {
                    String key = ep.path("endpointKey").asText("");
                    if (!key.isBlank()) {
                        folded.add(matchKeyRules.matchKeyFromLoose(key));
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A malformed snapshot arms nothing rather than failing dispatch:
            // the bus never blocks or duplicates, the reconcile job re-scans.
            return List.of();
        }
        return new ArrayList<>(folded);
    }
}

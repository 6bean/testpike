package com.sixbean.testpike.core.spec.domain.service.impl;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.core.spec.api.dto.InventoryEndpointView;
import com.sixbean.testpike.core.spec.api.dto.InventoryQuery;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import com.sixbean.testpike.core.spec.domain.component.DerivedMockKeyLookup;
import com.sixbean.testpike.core.spec.domain.component.MockProvenance;
import com.sixbean.testpike.core.spec.domain.service.ApiInventoryQueryService;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory read side over the anchored contract version.
 * <p>Anchor = max(spec_version) row of the name chain inside the project
 * (tp_api_spec has no published column — C-1 correction; the version chain IS
 * the release history). Asset counts join on the indexed folded
 * contract_endpoint_key column written by the save path — one
 * grouped query, never a per-row recompute. Freshness is the structure-hash
 * verdict against the anchor: endpoints that vanished from the anchor are
 * still listed as removed for traceability but leave the coverage denominator,
 * together with deprecated rows (R-9). The Mock column reads the same
 * derived-stub source as the twin badge, plus the read-time LLM-only
 * provenance note from the hit ledger (R-8).
 */
@Service
public class ApiInventoryQueryServiceImpl implements ApiInventoryQueryService {

    private static final String FRESH_STABLE = "stable";
    private static final String FRESH_CHANGED = "changed";
    private static final String FRESH_REMOVED = "removed";

    /** Default asset counters for endpoints nobody has built on yet. */
    private static final long[] ZERO_COUNTS = {0, 0};

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ApiSpecService apiSpecService;
    private final ContractMatchKeyRules matchKeyRules;
    private final DerivedMockKeyLookup derivedMockKeys;

    public ApiInventoryQueryServiceImpl(NamedParameterJdbcTemplate namedJdbc,
                                        ApiSpecService apiSpecService,
                                        ContractMatchKeyRules matchKeyRules,
                                        DerivedMockKeyLookup derivedMockKeys) {
        this.namedJdbc = namedJdbc;
        this.apiSpecService = apiSpecService;
        this.matchKeyRules = matchKeyRules;
        this.derivedMockKeys = derivedMockKeys;
    }

    @Override
    public PageResult<InventoryEndpointView> queryEndpoints(InventoryQuery query) {
        int size = PageQueryPolicy.enforceSize(query.pageSize());
        int page = Math.max(query.page(), 1);
        List<InventoryEndpointView> rows = buildRows(query.projectId(), query.specName());
        if (query.uncoveredOnly()) {
            // Server-side segment BEFORE paging (see InventoryQuery javadoc): the
            // badge filter must page over the working list, not a client window.
            rows = rows.stream()
                    .filter(r -> !r.excludedFromCoverage() && r.requestsCreated() == 0)
                    .toList();
        }
        long total = rows.size();
        int from = Math.min((page - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return new PageResult<>(rows.subList(from, to), total);
    }

    @Override
    public InventoryCoverageSummary queryCoverage(long projectId, String specName) {
        List<InventoryEndpointView> rows = buildRows(projectId, specName);
        int specVersion = apiSpecService.history(projectId, specName == null ? "" : specName)
                .stream().mapToInt(ApiSpecInfo::specVersion).max().orElse(0);
        int[] tally = tallyCoverage(rows);
        int percent = tally[0] == 0 ? 0 : (int) Math.round(tally[1] * 100.0 / tally[0]);
        return new InventoryCoverageSummary(projectId, specName, specVersion,
                tally[0], tally[1], tally[0] - tally[1], percent);
    }

    @Override
    public InventoryCoverageSummary projectCoverageSummary(long projectId) {
        // Project-wide gate measurement: every contract name chain measured on
        // its OWN anchor with the same tally — one coverage spec shared with the
        // inventory card (red line: never a second denominator source).
        List<String> names = namedJdbc.queryForList(
                "SELECT DISTINCT name FROM tp_api_spec WHERE project_id = :projectId ORDER BY name",
                new MapSqlParameterSource("projectId", projectId), String.class);
        int inScope = 0;
        int withRequests = 0;
        for (String name : names) {
            int[] tally = tallyCoverage(buildRows(projectId, name));
            inScope += tally[0];
            withRequests += tally[1];
        }
        int percent = inScope == 0 ? 0 : (int) Math.round(withRequests * 100.0 / inScope);
        return new InventoryCoverageSummary(projectId, null, 0,
                inScope, withRequests, inScope - withRequests, percent);
    }

    /** [denominator, numerator] over non-deprecated rows (shared tally rule). */
    private int[] tallyCoverage(List<InventoryEndpointView> rows) {
        int inScope = 0;
        int withRequests = 0;
        for (InventoryEndpointView r : rows) {
            if (r.excludedFromCoverage()) {
                continue;
            }
            inScope++;
            if (r.requestsCreated() > 0) {
                withRequests++;
            }
        }
        return new int[]{inScope, withRequests};
    }

    // ---------- shared row builder (page list and summary read ONE spec) ----------

    private List<InventoryEndpointView> buildRows(long projectId, String specName) {
        String trimmed = specName == null ? "" : specName.trim();
        List<ApiSpecInfo> chain = apiSpecService.history(projectId, trimmed);
        if (chain.isEmpty()) {
            return List.of();
        }
        ApiSpecInfo anchor = chain.get(chain.size() - 1); // history is version-ascending
        Set<String> anchorKeys = new HashSet<>();
        for (ApiEndpoint ep : anchor.endpoints()) {
            anchorKeys.add(ep.endpointKey());
        }

        Map<String, long[]> counts = assetCounts(projectId);
        Map<String, MockProvenance> mockProvenance = derivedMockKeys.lookup(projectId);

        // Changed verdicts come from the anchor row's own diff ledger (the
        // structure-hash comparison was done once at upload time — reading it
        // back keeps one source of truth, no re-hash here). First-version
        // chains carry no diff summary: everything is stable.
        Set<String> changedKeys = anchor.diffSummary() == null
                ? Set.of()
                : new HashSet<>(anchor.diffSummary().changed());
        List<InventoryEndpointView> rows = new ArrayList<>();
        for (ApiEndpoint ep : anchor.endpoints()) {
            String freshness = changedKeys.contains(ep.endpointKey()) ? FRESH_CHANGED : FRESH_STABLE;
            rows.add(view(ep, foldOf(ep), freshness, ep.deprecated(), counts, mockProvenance));
        }
        // Endpoints that existed in an older version but vanished from the anchor:
        // listed once, honesty first — never silently dropped from the inventory.
        Map<String, ApiEndpoint> removed = new LinkedHashMap<>();
        for (int i = chain.size() - 2; i >= 0; i--) {
            for (ApiEndpoint ep : chain.get(i).endpoints()) {
                if (!anchorKeys.contains(ep.endpointKey()) && !removed.containsKey(ep.endpointKey())) {
                    removed.put(ep.endpointKey(), ep);
                }
            }
        }
        removed.forEach((key, ep) -> rows.add(
                view(ep, foldOf(ep), FRESH_REMOVED, true, counts, mockProvenance)));

        rows.sort(Comparator.comparing(InventoryEndpointView::endpointKey));
        return rows;
    }

    /**
     * Requests and live promoted cases per folded contract key (equality on the
     * indexed column; soft-deleted cases never count).
     */
    private Map<String, long[]> assetCounts(long projectId) {
        Map<String, long[]> out = new HashMap<>();
        namedJdbc.query("""
                SELECT q.contract_endpoint_key AS epkey,
                       count(*) AS requests_created,
                       count(tc.id) AS cases_promoted
                  FROM tp_apitest_request q
                  JOIN tp_apitest_collection c ON c.id = q.collection_id
                  LEFT JOIN tp_test_case tc
                         ON tc.id = q.promoted_case_id AND tc.deleted = 0
                 WHERE c.project_id = :projectId
                   AND q.contract_endpoint_key IS NOT NULL
                 GROUP BY q.contract_endpoint_key
                """, new MapSqlParameterSource("projectId", projectId), rs -> {
            out.put(rs.getString("epkey"), new long[]{
                    rs.getLong("requests_created"), rs.getLong("cases_promoted")});
        });
        return out;
    }

    private InventoryEndpointView view(ApiEndpoint ep, String fold, String freshness,
                                       boolean excluded, Map<String, long[]> counts,
                                       Map<String, MockProvenance> mockProvenance) {
        long[] c = counts.getOrDefault(fold, ZERO_COUNTS);
        MockProvenance provenance = mockProvenance.get(fold);
        return new InventoryEndpointView(ep.method(), ep.path(), ep.endpointKey(),
                ep.title(), ep.parameters().size(), ep.securityRefs(),
                ep.deprecated(), excluded, freshness,
                (int) c[0], (int) c[1], provenance != null,
                provenance != null && provenance.llmOnly());
    }

    private String foldOf(ApiEndpoint ep) {
        return matchKeyRules.matchKey(ep.method(), ep.path());
    }
}

package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Catalog guidance rows: a zero-hit search never returns a bare empty list, and every hit path
 * stays clean of guidance rows.
 *
 * <p>Why this file exists: with an empty result the model behind the dialog gets {@code []} and
 * no explanation, which invites it to invent an entry name or to give up the conversation. The
 * door now answers a miss with a {@code __no_match__} row carrying what to do instead (drill by
 * domain prefix, reword the search) plus a domain-count summary built by the SAME grouping
 * component the layered index uses — one implementation, both consumers.
 *
 * <p>Registry lookups, quota, row bridges and audit are the usual stand-ins; the filtering,
 * verdict and guidance composition are the production classes.
 */
class ApiAccessCatalogGuidanceTest {

    private final ApiAccessCatalogTestHarness harness = new ApiAccessCatalogTestHarness();

    @BeforeEach
    void wireDoor() {
        harness.wireDoor();
    }

    private static ApiEndpointSpec spec(String method, String path, List<ApiEndpointParam> params) {
        return new ApiEndpointSpec(method + " " + path, method, path, params,
                !"GET".equals(method), null, null, List.of());
    }

    /** Reachable read entries spread over three domains. */
    private void stubReachableCatalog() {
        Map<String, ApiEndpointSpec> specs = new LinkedHashMap<>();
        specs.put("GET /api/v1/cases", spec("GET", "/api/v1/cases",
                List.of(new ApiEndpointParam("page", "query", false, "int", "1"))));
        specs.put("GET /api/v1/cases/{id}", spec("GET", "/api/v1/cases/{id}",
                List.of(new ApiEndpointParam("id", "path", true, "long", null))));
        specs.put("GET /api/v1/tags", spec("GET", "/api/v1/tags", List.of()));
        specs.put("GET /api/v1/executions/history", spec("GET", "/api/v1/executions/history", List.of()));
        when(harness.catalog.endpoints()).thenReturn(specs);
        Map<String, ApiAccessGovernanceView> overlay = new LinkedHashMap<>();
        for (String key : specs.keySet()) {
            overlay.put(key, new ApiAccessGovernanceView(key, true, true, "登记说明 " + key, null));
        }
        when(harness.governance.loadAll()).thenReturn(overlay);
    }

    /** Two reachable and one retired entry, used for the honest-zero branch. */
    private void stubReachableAndRetired() {
        Map<String, ApiEndpointSpec> specs = new LinkedHashMap<>();
        specs.put("GET /api/v1/cases", spec("GET", "/api/v1/cases", List.of()));
        specs.put("GET /api/v1/tags", spec("GET", "/api/v1/tags", List.of()));
        when(harness.catalog.endpoints()).thenReturn(specs);
        Map<String, ApiAccessGovernanceView> overlay = new LinkedHashMap<>();
        overlay.put("GET /api/v1/cases", new ApiAccessGovernanceView("GET /api/v1/cases",
                true, false, "已下线", "该面已迁到页面操作"));
        overlay.put("GET /api/v1/tags", new ApiAccessGovernanceView("GET /api/v1/tags",
                true, false, "已下线", "该面已迁到页面操作"));
        when(harness.governance.loadAll()).thenReturn(overlay);
    }

    private static int sumCounts(String guidanceText) {
        Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(guidanceText);
        int total = 0;
        while (m.find()) {
            total += Integer.parseInt(m.group(1));
        }
        return total;
    }

    @Test
    void zeroHitSearchDeliversGuidanceRowNotBareEmptyList() {
        stubReachableCatalog();

        List<ApiAccessEndpointView> rows = harness.door.catalog("zzz-不存在的检索词", true);

        assertEquals(1, rows.size(), "miss must deliver exactly the guidance row");
        ApiAccessEndpointView guidance = rows.get(0);
        assertEquals("__no_match__", guidance.key());
        assertNotNullDomainSummary(guidance.description());
        assertTrue(guidance.description().contains("api/v1"),
                "guidance must name reachable domain prefixes for drilling: " + guidance.description());
        assertTrue(guidance.description().contains("检索") || guidance.description().contains("词"),
                "guidance must suggest rewording the search: " + guidance.description());
    }

    @Test
    void guidanceDomainCountsSumToReachableTotal() {
        stubReachableCatalog();

        List<ApiAccessEndpointView> rows = harness.door.catalog("zzz-不存在的检索词", true);

        String guidanceText = rows.get(0).description();
        assertEquals(4, sumCounts(guidanceText),
                "per-domain counts must add up to the reachable total, got: " + guidanceText);
    }

    @Test
    void hitPathStaysCleanWithoutGuidanceRow() {
        stubReachableCatalog();

        List<ApiAccessEndpointView> rows = harness.door.catalog("execution", true);

        assertFalse(rows.stream().anyMatch(r -> "__no_match__".equals(r.key())),
                "guidance row must never pollute a hit path");
        assertEquals(List.of("GET /api/v1/executions/history"),
                rows.stream().map(ApiAccessEndpointView::key).toList());
    }

    @Test
    void zeroReachableEnvironmentNamesNoReadableEntriesInsteadOfEmptySummary() {
        stubReachableAndRetired();

        List<ApiAccessEndpointView> rows = harness.door.catalog("zzz-不存在的检索词", true);

        String guidanceText = rows.get(0).description();
        assertTrue(guidanceText.contains("当前无可直读条目"),
                "R43: the zero-count branch must say so plainly, got: " + guidanceText);
    }

    private static void assertNotNullDomainSummary(String text) {
        assertTrue(text != null && !text.isBlank(), "guidance row needs a non-blank summary");
    }
}

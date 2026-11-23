package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sixbean.testpike.core.apiaccess.domain.service.impl.ApiAccessCatalogTestHarness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Catalog browse shape when a blind (empty-keyword) listing exceeds the row ceiling: the door
 * delivers a per-domain index instead of a truncated segment of details.
 *
 * <p>Why this file exists: a 229-entry ledger truncated to the first ceiling rows in
 * lexicographic order teaches the dialog model almost nothing about what else exists — it never
 * learns which families it could still drill into. The index answers one row per domain
 * (prefix + entry count + a one-line hint naming a sample entry); feeding the prefix back as the
 * keyword hits the EXISTING substring search, so drilling needs no new parameter. Two shapes are
 * pinned against drift: totals inside the index must add up to the reachable count (hard audit,
 * R43), and everything outside the trigger (fits under the ceiling, carries a keyword, or lists
 * blocked entries too) keeps the pre-change detail rows untouched.
 *
 * <p>Domain counting reuses {@code ApiAccessDomainGrouper} — the same single source that renders
 * the zero-hit guidance row (R40); the folding of extra domains is governed by the hot-updatable
 * ceiling key {@code ai.api-access.catalog-group-max}.
 */
class ApiAccessCatalogDomainIndexTest {

    private final ApiAccessCatalogTestHarness harness = new ApiAccessCatalogTestHarness();

    @BeforeEach
    void wireDoor() {
        harness.wireDoor();
    }

    /** Five reachable read entries spread over two domains (api/v1 x3, internal/projects x2). */
    private void stubTwoDomains() {
        Map<String, ApiEndpointSpec> specs = new LinkedHashMap<>();
        specs.put("GET /api/v1/cases", spec("GET", "/api/v1/cases"));
        specs.put("GET /api/v1/tags", spec("GET", "/api/v1/tags"));
        specs.put("GET /api/v1/executions/history", spec("GET", "/api/v1/executions/history"));
        specs.put("GET /internal/projects", spec("GET", "/internal/projects"));
        specs.put("GET /internal/projects/{id}/members", spec("GET", "/internal/projects/{id}/members"));
        when(harness.catalog.endpoints()).thenReturn(specs);
        Map<String, ApiAccessGovernanceView> overlay = new LinkedHashMap<>();
        for (String key : specs.keySet()) {
            overlay.put(key, new ApiAccessGovernanceView(key, true, true, "登记说明 " + key, null));
        }
        when(harness.governance.loadAll()).thenReturn(overlay);
    }

    
    private static int sumIndexCounts(List<ApiAccessEndpointView> rows) {
        Matcher m = Pattern.compile("共 (\\d+) 条").matcher(
                String.join(" | ", rows.stream().map(ApiAccessEndpointView::description).toList()));
        int total = 0;
        while (m.find()) {
            total += Integer.parseInt(m.group(1));
        }
        return total;
    }

    @Test
    void emptyBrowseOverCeilingDeliversDomainIndexNotTruncatedSlice() {
        stubTwoDomains();
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "1");

        List<ApiAccessEndpointView> rows = harness.door.catalog("", true);

        assertEquals(2, rows.size(), "one row per domain, got: " + rows);
        assertTrue(rows.stream().allMatch(r -> r.key().startsWith("__domain__")),
                "index rows must be marked as index rows, got: " + rows);
        assertTrue(rows.get(0).description().contains("api/v1")
                        && rows.get(0).description().contains("共 3 条"),
                "biggest family first with its prefix and count, got: " + rows.get(0));
        assertTrue(rows.get(0).description().contains("keyword"),
                "index row must tell the reader how to drill (prefix as keyword), got: " + rows.get(0));
        assertEquals(5, sumIndexCounts(rows),
                "hard audit: per-domain counts must add up to the reachable total");
    }

    @Test
    void domainPrefixFromIndexDrillsThroughExistingKeywordSearch() {
        stubTwoDomains();

        // No new parameter: the prefix shown in an index row is fed back as the plain keyword and
        // hits the existing substring match on the path (ceiling high enough to show all details).
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "120");
        List<ApiAccessEndpointView> drilled = harness.door.catalog("api/v1", true);

        assertEquals(List.of("GET /api/v1/cases", "GET /api/v1/executions/history", "GET /api/v1/tags"),
                drilled.stream().map(ApiAccessEndpointView::key).sorted().toList(),
                "drill-down must return that family's detail rows");
    }

    @Test
    void browseWithinCeilingKeepsDetailRowsUnchanged() {
        stubTwoDomains();
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "120");

        List<ApiAccessEndpointView> rows = harness.door.catalog(null, true);

        assertEquals(5, rows.size());
        assertFalse(rows.stream().anyMatch(r -> r.key().startsWith("__")),
                "a browse that fits must ship pure details, no index or truncation rows");
    }

    @Test
    void keywordSearchOverCeilingKeepsTruncationRowShape() {
        stubTwoDomains();
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "1");

        List<ApiAccessEndpointView> rows = harness.door.catalog("GET /", true);

        assertTrue(rows.stream().anyMatch(r -> "__truncated__".equals(r.key())),
                "non-empty keyword outside the trigger keeps the previous truncation shape");
        assertFalse(rows.stream().anyMatch(r -> r.key().startsWith("__domain__")),
                "index shape is reserved for the empty-keyword reachable browse");
    }

    @Test
    void unreachableListedBrowseOverCeilingKeepsTruncationRowShape() {
        stubTwoDomains();
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "1");

        List<ApiAccessEndpointView> rows = harness.door.catalog("", false);

        assertTrue(rows.stream().anyMatch(r -> "__truncated__".equals(r.key())),
                "onlyReachable=false keeps details-with-reasons, truncation included");
        assertFalse(rows.stream().anyMatch(r -> r.key().startsWith("__domain__")),
                "index trigger requires onlyReachable=true (blocked rows carry no index value)");
    }

    @Test
    void domainsBeyondGroupCeilingFoldIntoRestRowNamingCounts() {
        stubTwoDomains();
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "1");
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_GROUP_MAX, "1");

        List<ApiAccessEndpointView> rows = harness.door.catalog("", true);

        assertEquals(2, rows.size(), "one listed domain plus the folded rest row");
        ApiAccessEndpointView rest = rows.get(1);
        assertEquals("__domains_rest__", rest.key());
        assertTrue(rest.description().contains("其余域") || rest.description().contains("另有 1 个域"),
                "rest row must name the folded domains honestly, got: " + rest.description());
        assertTrue(rest.description().contains("2"),
                "rest row must name the folded entry count, got: " + rest.description());
    }
}

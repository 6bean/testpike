package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Catalog delivery order when a keyword search matches more entries than the row ceiling allows.
 *
 * <p>Why this file exists: the door used to sort every keyword hit by entry name and then cut the
 * description) shipped the alphabetically-first segment and silently dropped the one entry the
 * registered synonym vocabulary actually points at. Measured on the live ledger: 224 reachable
 * description prose mentions it — a lexicographic cut therefore kept mostly prose echoes and threw
 * away the direct answers. The dialog model never learns those entries exist and invents a key.
 *
 * <p>The ranking is a relevance-first cut: registered synonyms that name the keyword exactly come
 * first, then entries whose synonym token or path carries it, then description echoes; inside each
 * tier the previous name order is kept so nothing else moves. Only the truncating branch changes —
 * a view that fits under the ceiling is delivered exactly as before (no reordering, no new rows),
 * and the zero-hit guidance row is untouched.
 *
 * <p>Catalog registry and governance storage are the usual stand-ins; filtering, verdict, ranking,
 * ceiling and guidance composition are the production classes.
 */
class ApiAccessCatalogRelevanceOrderTest {

    private final ApiAccessCatalogTestHarness harness = new ApiAccessCatalogTestHarness();

    @BeforeEach
    void wireDoor() {
        harness.wireDoor();
    }

    private static ApiEndpointSpec read(String method, String path) {
        return new ApiEndpointSpec(method + " " + path, method, path, List.of(),
                !"GET".equals(method), null, null, List.of());
    }

    /** Governance row with a curated synonym column (the search corpus of that entry). */
    private static ApiAccessGovernanceView corpus(String key, String description, String searchTerms) {
        return new ApiAccessGovernanceView(key, true, true, description, null, null, null, null,
                searchTerms);
    }

    /**
     * The live shape that broke: a handful of entries whose description merely mentions the keyword
     * (they sort first by name) plus the direct answers further down the alphabet.
     *
     * @param headCount description-echo entries with keys that sort before every other key here
     */
    private void stubEchoesAheadOfCorpusHits(int headCount) {
        Map<String, ApiEndpointSpec> specs = new LinkedHashMap<>();
        Map<String, ApiAccessGovernanceView> overlay = new LinkedHashMap<>();
        for (int i = 1; i <= headCount; i++) {
            String key = "GET /api/v1/a-" + i;
            specs.put(key, read("GET", "/api/v1/a-" + i));
            overlay.put(key, corpus(key, "登记说明：与项目无关的条目，仅在说明里提到 项目 一词", null));
        }
        // Registered synonym equal to the keyword — the strongest human-vocabulary signal.
        specs.put("GET /internal/projects", read("GET", "/internal/projects"));
        overlay.put("GET /internal/projects", corpus("GET /internal/projects", "列出全部项目", "项目,projects"));
        specs.put("GET /internal/user-projects", read("GET", "/internal/user-projects"));
        overlay.put("GET /internal/user-projects",
                corpus("GET /internal/user-projects", "列出登录人可见的项目", "用户,user-projects,我的项目"));
        when(harness.catalog.endpoints()).thenReturn(specs);
        when(harness.governance.loadAll()).thenReturn(overlay);
    }

    private static List<String> keys(List<ApiAccessEndpointView> rows) {
        return rows.stream().map(ApiAccessEndpointView::key).toList();
    }

    @Test
    void registeredSynonymTokenSurvivesAcutThatLexicographicOrderWouldDrop() {
        stubEchoesAheadOfCorpusHits(5);
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "3");

        List<ApiAccessEndpointView> rows = harness.door.catalog("项目", true);

        // 3 delivered details + the truncation marker.
        assertEquals(4, rows.size(), "ceiling plus marker, got: " + keys(rows));
        assertEquals("GET /internal/projects", rows.get(0).key(),
                "the entry whose registered synonym names the keyword exactly must be first, got: "
                        + keys(rows));
        assertTrue(keys(rows).contains("GET /internal/user-projects"),
                "a synonym token containing the keyword outranks description echoes, got: "
                        + keys(rows));
    }

    @Test
    void truncationMarkerStillNamesTheTotalAndTheCeilingKey() {
        stubEchoesAheadOfCorpusHits(5);
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "3");

        List<ApiAccessEndpointView> rows = harness.door.catalog("项目", true);

        ApiAccessEndpointView marker = rows.stream()
                .filter(r -> "__truncated__".equals(r.key()))
                .findFirst().orElseThrow(() -> new AssertionError("marker row missing: " + keys(rows)));
        assertTrue(marker.reason().contains("共 7 条"),
                "marker must state the real total, got: " + marker.reason());
        assertTrue(marker.reason().contains(ApiAccessServiceImpl.KEY_CATALOG_MAX),
                "marker must name the hot-updatable ceiling parameter, got: " + marker.reason());
        assertTrue(marker.reason().contains("相关度"),
                "marker must tell the reader the view is ordered by relevance, got: "
                        + marker.reason());
    }

    @Test
    void aViewThatFitsUnderTheCeilingKeepsPlainNameOrder() {
        stubEchoesAheadOfCorpusHits(3);
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "120");

        List<ApiAccessEndpointView> rows = harness.door.catalog("项目", true);

        List<String> sorted = new ArrayList<>(keys(rows));
        sorted.sort(null);
        assertEquals(sorted, keys(rows), "no truncation means no reordering, got: " + keys(rows));
        assertFalse(keys(rows).stream().anyMatch(k -> k.startsWith("__")),
                "a complete view ships pure details, got: " + keys(rows));
    }

    @Test
    void aKeywordThatMatchesNothingStillDeliversTheGuidanceRow() {
        stubEchoesAheadOfCorpusHits(2);
        harness.configCache.put(ApiAccessServiceImpl.KEY_CATALOG_MAX, "3");

        List<ApiAccessEndpointView> rows = harness.door.catalog("完全无关的词", true);

        assertEquals(List.of("__no_match__"), keys(rows),
                "the zero-hit guidance row is not part of this change, got: " + keys(rows));
    }
}

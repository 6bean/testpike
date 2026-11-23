package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sixbean.testpike.core.apiaccess.domain.service.impl.ApiAccessCatalogTestHarness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Catalog keyword search consumes the curated synonym column of the governance overlay.
 *
 * <p>Why this file exists: the search surface used to be endpoint_key, parameter names and the
 * registered description only. Descriptions are written as verb+object sentences, so a dialog
 * entry, and the row was invisible even though it was reachable. The V413 column
 * {@code search_terms} carries registered synonyms for exactly that gap — but a DB column is
 * not a feature until a reader uses it. These tests pin the reading face: a synonym-only term
 * now hits, and the column being absent (null) or holding a different word changes nothing, so
 * the new surface can never widen into "match everything".
 *
 * <p>Registered vocabulary discipline (what rows may put into the column) is enforced by the
 * corpus gate on the tooling side, not here; this class only pins the runtime matcher.
 */
class ApiAccessCatalogSearchCorpusTest {

    private final ApiAccessCatalogTestHarness harness = new ApiAccessCatalogTestHarness();

    @BeforeEach
    void wireDoor() {
        harness.wireDoor();
    }

    /**
     * Two reachable read entries: the tag dictionary carries registered synonyms, the case list
     * carries none — the control row proving null search_terms keeps the old surface.
     */
    private void stubCorpusRows() {
        Map<String, ApiEndpointSpec> specs = new LinkedHashMap<>();
        specs.put("GET /api/v1/tags", spec("GET", "/api/v1/tags"));
        specs.put("GET /api/v1/cases", spec("GET", "/api/v1/cases"));
        when(harness.catalog.endpoints()).thenReturn(specs);
        Map<String, ApiAccessGovernanceView> overlay = new LinkedHashMap<>();
        overlay.put("GET /api/v1/tags", new ApiAccessGovernanceView("GET /api/v1/tags",
                true, true, "查询标签字典列表", null, null, null, null, "标签字典,tag dictionary"));
        overlay.put("GET /api/v1/cases", new ApiAccessGovernanceView("GET /api/v1/cases",
                true, true, "查询用例列表", null, null, null, null, null));
        when(harness.governance.loadAll()).thenReturn(overlay);
    }

    private static List<String> hitKeys(List<ApiAccessEndpointView> rows) {
        return rows.stream().map(ApiAccessEndpointView::key).toList();
    }

    @Test
    void synonymOnlyTermHitsTheEntryTheDescriptionNeverMentions() {
        stubCorpusRows();

        // "tag dictionary" appears in NO key, NO parameter, NO description of the row it must hit;
        // it exists only inside the registered synonym column.
        List<ApiAccessEndpointView> rows = harness.door.catalog("tag dictionary", true);

        assertEquals(List.of("GET /api/v1/tags"), hitKeys(rows),
                "synonym column must widen the keyword surface, got: " + rows);
    }

    @Test
    void chineseSynonymTermHitsWithoutCaseFoldingSurprises() {
        stubCorpusRows();

        List<ApiAccessEndpointView> rows = harness.door.catalog("标签字典", true);

        assertEquals(List.of("GET /api/v1/tags"), hitKeys(rows),
                "registered Chinese vocabulary must hit through the same substring face, got: " + rows);
    }

    @Test
    void rowWithoutRegisteredSynonymsStaysInvisibleToSynonymOnlyTerms() {
        stubCorpusRows();

        // The synonym of the tag row must NOT leak onto the control row: a null column keeps the
        // pre-change surface, so widening is per registered row and never blanket.
        assertTrue(hitKeys(harness.door.catalog("dictionary", true)).contains("GET /api/v1/tags"));
        assertEquals(List.of(), harness.door.catalog("nonexistent wording", true).stream()
                        .filter(r -> !r.key().equals("__no_match__"))
                        .toList(),
                "a term found nowhere must still deliver only the guidance row");
        assertEquals(List.of("GET /api/v1/cases"), hitKeys(harness.door.catalog("用例", true)),
                "description search keeps working exactly as before for rows without synonyms");
    }
}

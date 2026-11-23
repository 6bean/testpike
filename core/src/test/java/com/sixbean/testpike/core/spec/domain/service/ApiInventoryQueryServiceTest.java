package com.sixbean.testpike.core.spec.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.core.spec.api.dto.InventoryEndpointView;
import com.sixbean.testpike.core.spec.api.dto.InventoryQuery;
import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.OpenApiSpecParser;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract inventory query service of the API inventory page).
 * <p>Spectra: A parser-side deprecated flag (single-source parse, structure hash
 * deliberately excludes it so a deprecation toggle never fires a contract "changed"
 * badge; legacy snapshots without the key deserialize to false); B endpoint rows
 * anchored to the max(spec_version) row of the name chain with deprecated and
 * removed rows still listed but excluded from the coverage denominator; C asset
 * counts joined by equality on the indexed contract_endpoint_key column (no
 * per-row recompute) with promoted cases counting live tp_test_case rows only;
 * D mock column from project-scoped openapi-sourced stubs plus the honest
 * AI-fallback provenance note; E coverage summary; F project isolation and
 * paging (worst-scale rule: endpoint rows reach the hundreds).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class ApiInventoryQueryServiceTest {

    @Autowired
    private ApiInventoryQueryService inventoryService;
    @Autowired
    private ApiSpecService apiSpecService;
    @Autowired
    private ApiRequestService requestService;
    @Autowired
    private ApiCollectionService collectionService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long collectionId;

    /** Project-keyed tables without an FK into tp_project (live schema scan). */
    private static final java.util.List<String> SWEEP_EXTRA =
            java.util.List.of("tp_api_spec", "tp_mock_space");

    /** Fixture projects swept by FK tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureProjects = new java.util.ArrayList<>();

    @BeforeEach
    void cleanAndSeed() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("清单项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
        ApiCollectionInfo collection = collectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "清单集" + code));
        collectionId = collection.id();
    }

    @AfterEach
    void sweepFixtures() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, SWEEP_EXTRA, fixtureProjects);
        fixtureProjects.clear();
    }

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    // ---------- SPECTRUM A: parser-side deprecated ----------

    @Test
    void parserReadsDeprecatedFlagButKeepsStructureHashStable() {
        OpenApiSpecParser parser = new OpenApiSpecParser();
        String plain = """
                {"openapi":"3.0.1","info":{"title":"A","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                   "responses":{"200":{"description":"ok"}}}}}}""";
        String deprecatedDoc = """
                {"openapi":"3.0.1","info":{"title":"A","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets","deprecated":true,
                   "responses":{"200":{"description":"ok"}}}}}}""";
        ApiEndpoint plainEp = endpoint(parser.parse(json(plain), "openapi3-json"), "GET /pets");
        ApiEndpoint depEp = endpoint(parser.parse(json(deprecatedDoc), "openapi3-json"), "GET /pets");
        assertFalse(plainEp.deprecated());
        assertTrue(depEp.deprecated());
        // Deprecation is a lifecycle note, not a structure change: same fingerprint.
        assertEquals(plainEp.structureHash(), depEp.structureHash());
    }

    @Test
    void legacySnapshotWithoutDeprecatedKeyDeserializesToFalse() throws Exception {
        String legacySnapshot = """
                [{"method":"GET","path":"/pets","endpointKey":"GET /pets",
                  "operationId":"listPets","title":"列表","parameters":[],
                  "responseSchemas":{},"securityRefs":[],
                  "structureHash":"abc"}]""";
        List<ApiEndpoint> eps = new ObjectMapper().readValue(legacySnapshot,
                new TypeReference<List<ApiEndpoint>>() { });
        assertEquals(1, eps.size());
        assertFalse(eps.get(0).deprecated());
    }

    // ---------- SPECTRUM B: anchored endpoint rows ----------

    @Test
    void queryAnchorsLatestVersionAndListsDeprecatedAndRemovedRows() {
        upload("inv-api", """
                {"openapi":"3.0.1","info":{"title":"I","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                     "responses":{"200":{"description":"ok"}}}},
                   "/legacy":{"get":{"operationId":"legacy",
                     "responses":{"200":{"description":"ok"}}}}}}""");
        upload("inv-api", """
                {"openapi":"3.0.1","info":{"title":"I","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets","deprecated":true,
                     "responses":{"200":{"description":"ok"}}}},
                   "/orders":{"post":{"operationId":"createOrder",
                     "responses":{"201":{"description":"c"}}}}}}""");
        List<InventoryEndpointView> rows = pages(
                inventoryService.queryEndpoints(new InventoryQuery(projectId, "inv-api", 1, 50, false)));
        assertEquals(3, rows.size(), "latest row endpoints + removed-from-prev row");
        InventoryEndpointView pets = find(rows, "GET /pets");
        assertTrue(pets.deprecated());
        assertTrue(pets.excludedFromCoverage(), "deprecated stays listed but off the denominator");
        assertEquals("stable", pets.freshness(), "deprecated is lifecycle, not contract change");
        InventoryEndpointView removed = find(rows, "GET /legacy");
        assertEquals("removed", removed.freshness());
        assertTrue(removed.excludedFromCoverage());
        assertFalse(find(rows, "POST /orders").excludedFromCoverage());
    }

    @Test
    void anchorRowsCarryChangedFreshnessFromTheVersionDiffLedger() {
        // The three-state freshness contract (InventoryEndpointView javadoc)
        // promises a "changed" tint for endpoints whose structure hash moved
        // between the anchor and its predecessor; the verdict must come from
        // the anchor row's own diffSummary ledger (single source — never a
        // second hash comparison here).
        upload("churn-api", """
                {"openapi":"3.0.1","info":{"title":"I","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                     "responses":{"200":{"description":"ok"}}}},
                   "/toys":{"get":{"operationId":"listToys",
                     "responses":{"200":{"description":"ok"}}}}}}""");
        upload("churn-api", """
                {"openapi":"3.0.1","info":{"title":"I","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                     "parameters":[{"name":"q","in":"query","required":false,
                       "schema":{"type":"string"}}],
                     "responses":{"200":{"description":"ok"}}}},
                   "/toys":{"get":{"operationId":"listToys",
                     "responses":{"200":{"description":"ok"}}}}}}""");
        List<InventoryEndpointView> rows = pages(
                inventoryService.queryEndpoints(new InventoryQuery(projectId, "churn-api", 1, 50, false)));
        assertEquals(2, rows.size());
        assertEquals("changed", find(rows, "GET /pets").freshness(),
                "structure-hash move must wear the changed badge");
        assertEquals("stable", find(rows, "GET /toys").freshness(),
                "untouched endpoints stay stable next to a changed sibling");
        // A changed row stays inside the coverage denominator — it is live
        // contract surface, unlike removed/deprecated rows.
        assertFalse(find(rows, "GET /pets").excludedFromCoverage());
    }

    // ---------- SPECTRUM C: asset counts by indexed key equality ----------

    @Test
    void assetCountsJoinOnFoldedContractKeyAndCountLiveCasesOnly() {
        upload("count-api", """
                {"openapi":"3.0.1","info":{"title":"C","version":"1"},
                 "paths":{"/pets/{petId}":{"get":{"operationId":"getPet",
                     "parameters":[{"name":"petId","in":"path","required":true,
                       "schema":{"type":"string"}}],
                     "responses":{"200":{"description":"ok"}}}}}}""");
        // Hand-written concrete path must fold onto the template endpoint key.
        long reqId = createRequest("GET", "/pets/12345").id();
        long liveCase = newCase(false);
        long deadCase = newCase(true);
        jdbcTemplate.update("UPDATE tp_apitest_request SET promoted_case_id = ? WHERE id = ?",
                liveCase, reqId);
        long deadReqId = createRequest("GET", "/pets/99").id();
        jdbcTemplate.update("UPDATE tp_apitest_request SET promoted_case_id = ? WHERE id = ?",
                deadCase, deadReqId);

        List<InventoryEndpointView> rows = pages(
                inventoryService.queryEndpoints(new InventoryQuery(projectId, "count-api", 1, 50, false)));
        InventoryEndpointView ep = find(rows, "GET /pets/{petId}");
        assertEquals(2, ep.requestsCreated(), "both concrete requests fold to the template key");
        assertEquals(1, ep.casesPromoted(), "soft-deleted case must not count");
    }

    // ---------- SPECTRUM D: mock column with honest provenance ----------

    @Test
    void mockColumnReflectsOpenapiStubsAndLlmFallbackProvenance() {
        upload("mock-api", """
                {"openapi":"3.0.1","info":{"title":"M","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                     "responses":{"200":{"description":"ok"}}}},
                   "/orders":{"get":{"operationId":"listOrders",
                     "responses":{"200":{"description":"ok"}}}}}}""");
        long spaceId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_mock_space (project_id, name, space_key)
                VALUES (?, '清单空间', ?) RETURNING id""",
                Long.class, projectId, "inv-" + UUID.randomUUID().toString().substring(0, 8));
        long stubId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_mock_stub (space_id, name, method, path_pattern, source)
                VALUES (?, '宠物桩', 'GET', '/pets', 'openapi') RETURNING id""",
                Long.class, spaceId);
        // The only recorded answer for this stub came from the LLM fallback —
        // claiming "covered" without the note would be a lie.
        jdbcTemplate.update("""
                INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, source)
                VALUES (?, ?, TRUE, 'llm')""", spaceId, stubId);

        List<InventoryEndpointView> rows = pages(
                inventoryService.queryEndpoints(new InventoryQuery(projectId, "mock-api", 1, 50, false)));
        InventoryEndpointView pets = find(rows, "GET /pets");
        assertTrue(pets.mockCovered());
        assertTrue(pets.mockAiFallbackOnly(), "all-llm ledger shows the unverified note");
        assertFalse(find(rows, "GET /orders").mockCovered());
    }

    // ---------- SPECTRUM E: coverage summary ----------

    @Test
    void coverageDenominatorIsLatestNonDeprecatedEndpoints() {
        upload("cov-api", """
                {"openapi":"3.0.1","info":{"title":"V","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                     "responses":{"200":{"description":"ok"}}}},
                   "/pets/{petId}":{"get":{"operationId":"getPet",
                     "responses":{"200":{"description":"ok"}}}},
                   "/old":{"get":{"operationId":"oldEp","deprecated":true,
                     "responses":{"200":{"description":"ok"}}}}}}""");
        createRequest("GET", "/pets");
        InventoryCoverageSummary summary =
                inventoryService.queryCoverage(projectId, "cov-api");
        assertEquals(2, summary.total(), "deprecated endpoint leaves the denominator");
        assertEquals(1, summary.endpointsWithRequests());
        assertEquals(1, summary.uncoveredCount());
        assertEquals(50, summary.coveragePercent());
        assertEquals(1, summary.specVersion());
    }

    // ---------- SPECTRUM F2: uncovered-only server-side segment ----------

    @Test
    void uncoveredOnlySlicesBeforePagingAndSkipsOffDenominatorRows() {
        upload("uncov-api", """
                {"openapi":"3.0.1","info":{"title":"U","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                     "responses":{"200":{"description":"ok"}}}},
                   "/orders":{"get":{"operationId":"listOrders",
                     "responses":{"200":{"description":"ok"}}}},
                   "/report":{"post":{"operationId":"sendReport",
                     "responses":{"200":{"description":"ok"}}}},
                   "/legacy":{"get":{"operationId":"legacyEp","deprecated":true,
                     "responses":{"200":{"description":"ok"}}}}}}""");
        createRequest("GET", "/pets");
        var all = inventoryService.queryEndpoints(
                new InventoryQuery(projectId, "uncov-api", 1, 50, false));
        assertEquals(4, all.total(), "unfiltered list keeps every anchored row");
        var uncovered = inventoryService.queryEndpoints(
                new InventoryQuery(projectId, "uncov-api", 1, 50, true));
        // Honest denominator parity: the segment must equal total-with-requests
        // from the coverage card (2 here) — a deprecated endpoint that nobody
        // built on is off the denominator, so calling it "uncovered" would
        // contradict the coverage card on the same page.
        assertEquals(2, uncovered.total());
        assertEquals(2, uncovered.list().size());
        assertTrue(uncovered.list().stream()
                .noneMatch(r -> r.endpointKey().equals("GET /pets")),
                "an endpoint with requests is never uncovered");
        find(uncovered.list(), "GET /orders");
        find(uncovered.list(), "POST /report");
        // Paging window applies AFTER the segment, so page math stays honest at
        // worst scale (hundreds of endpoints, no client-window filtering).
        var page1 = inventoryService.queryEndpoints(
                new InventoryQuery(projectId, "uncov-api", 1, 1, true));
        assertEquals(2, page1.total());
        assertEquals(1, page1.list().size());
    }

    // ---------- SPECTRUM F: isolation and paging ----------

    @Test
    void otherProjectSeesNothing() {
        upload("iso-api", """
                {"openapi":"3.0.1","info":{"title":"S","version":"1"},
                 "paths":{"/pets":{"get":{"operationId":"listPets",
                     "responses":{"200":{"description":"ok"}}}}}}""");
        long other = projectService.create(1L, new ProjectCreateInput("别的项目",
                "iso-" + UUID.randomUUID().toString().substring(0, 6), "zh-CN", "P1", null));
        assertEquals(0, inventoryService
                .queryEndpoints(new InventoryQuery(other, "iso-api", 1, 50, false)).total());
    }

    @Test
    void endpointRowsPaginateAndOversizeIsRejected() {
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            paths.append("\"/r").append(i).append("\":{\"get\":{\"operationId\":\"o")
                    .append(i).append("\",\"responses\":{\"200\":{\"description\":\"ok\"}}}},");
        }
        upload("wide-api", """
                {"openapi":"3.0.1","info":{"title":"W","version":"1"},"paths":{%s}}"""
                .formatted(paths.toString().replaceAll(",$", "")));
        var page1 = inventoryService.queryEndpoints(
                new InventoryQuery(projectId, "wide-api", 1, 10, false));
        assertEquals(25, page1.total());
        assertEquals(10, page1.list().size());
        assertEquals(5, inventoryService.queryEndpoints(
                new InventoryQuery(projectId, "wide-api", 3, 10, false)).list().size());
        assertThrows(ValidationException.class, () -> inventoryService.queryEndpoints(
                new InventoryQuery(projectId, "wide-api", 1, 201, false)));
    }

    // ---------- helpers ----------

    private void upload(String name, String body) {
        apiSpecService.upload(1L, projectId, name, json(body), null);
    }

    private ApiRequestInfo createRequest(String method, String path) {
        return requestService.create(1L, new RequestSaveInput(collectionId,
                "请求" + UUID.randomUUID().toString().substring(0, 6), method, path,
                null, null, null, null));
    }

    private long newCase(boolean deleted) {
        String no = "INV-" + UUID.randomUUID().toString().substring(0, 10);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title, deleted) "
                        + "VALUES (?, ?, '清单夹具单', ?) RETURNING id",
                Long.class, no, projectId, deleted ? 1 : 0);
        assertNotNull(id);
        return id;
    }

    private static ApiEndpoint endpoint(ParsedApiSpec parsed, String key) {
        return parsed.endpoints().stream()
                .filter(e -> e.endpointKey().equals(key))
                .findFirst().orElseThrow(() -> new AssertionError("端点缺失: " + key));
    }

    private static List<InventoryEndpointView> pages(
            com.sixbean.testpike.core.common.clientobject.PageResult<InventoryEndpointView> p) {
        return p.list();
    }

    private static InventoryEndpointView find(List<InventoryEndpointView> rows, String key) {
        return rows.stream().filter(r -> r.endpointKey().equals(key))
                .findFirst().orElseThrow(() -> new AssertionError("清单行缺失: " + key));
    }
}

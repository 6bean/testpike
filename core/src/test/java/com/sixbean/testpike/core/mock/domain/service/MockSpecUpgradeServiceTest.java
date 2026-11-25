package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.domain.component.MockRuleKeys;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.component.SpecUpgradePlan;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-upgrade difference surface tests: re-uploading a contract under the
 * same name files a new version chained through prev_spec_id; the planner must
 * split the endpoint drift into added / changed / removed against exactly the
 * rules this space imported from the contract family, and the marking step must
 * disable + flag disappeared rules without deleting rows or touching the hit
 * ledger (replay files zero changes).
 * <p>All against the real dev database (SpringBootTest, zero mocks —
 * MockStubImportServiceTest posture).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockSpecUpgradeServiceTest {

    private static final String V1_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Upgrade API", "version": "1.0.0"},
              "paths": {
                "/orders/{id}": {
                  "get": {
                    "operationId": "getOrder",
                    "summary": "查询订单详情",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true,
                       "schema": {"type": "integer"}}
                    ],
                    "responses": {"200": {"description": "ok",
                        "content": {"application/json": {"schema": {"type": "object"}}}}}
                  }
                },
                "/orders": {
                  "post": {
                    "operationId": "createOrder",
                    "summary": "创建订单",
                    "responses": {"201": {"description": "created",
                        "content": {"application/json": {"schema": {"type": "object"}}}}}
                  }
                },
                "/legacy": {
                  "get": {
                    "operationId": "legacyPing",
                    "summary": "旧接口",
                    "responses": {"200": {"description": "ok",
                        "content": {"application/json": {"schema": {"type": "object"}}}}}
                  }
                }
              }
            }
            """;

    /**
     * v2 keeps GET /orders/{id} byte-shape identical (must NOT appear as
     * changed), gives POST /orders a response property (changed), introduces
     * GET /shipments (added) and drops GET /legacy (removed).
     */
    private static final String V2_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Upgrade API", "version": "2.0.0"},
              "paths": {
                "/orders/{id}": {
                  "get": {
                    "operationId": "getOrder",
                    "summary": "查询订单详情",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true,
                       "schema": {"type": "integer"}}
                    ],
                    "responses": {"200": {"description": "ok",
                        "content": {"application/json": {"schema": {"type": "object"}}}}}
                  }
                },
                "/orders": {
                  "post": {
                    "operationId": "createOrder",
                    "summary": "创建订单",
                    "responses": {"201": {"description": "created",
                        "content": {"application/json": {"schema": {
                            "type": "object",
                            "properties": {"orderNo": {"type": "string"}}}}}}}
                  }
                },
                "/shipments": {
                  "get": {
                    "operationId": "listShipments",
                    "summary": "查询发货单",
                    "responses": {"200": {"description": "ok",
                        "content": {"application/json": {"schema": {"type": "object"}}}}}
                  }
                }
              }
            }
            """;

    @Autowired
    private MockSpecUpgradeService upgradeService;

    @Autowired
    private MockStubImportService importService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private ApiSpecService apiSpecService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "mjsu" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "upg-" + UUID.randomUUID().toString().substring(0, 8);
        return spaceService.create(projectId, new com.sixbean.testpike.core.mock.api.dto.MockSpaceInput(
                "Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    private long uploadSpec(long projectId, String name, String json) {
        ApiSpecInfo info = apiSpecService.upload(1L, projectId, name,
                json.getBytes(StandardCharsets.UTF_8), null);
        return info.id();
    }

    private long spaceIdForImportedContract(long projectId, long specId, long spaceId) {
        importService.importDrafts(spaceId, importService.previewDrafts(specId));
        return spaceId;
    }

    private long stubIdOf(long spaceId, String method, String path) {
        String want = MockRuleKeys.of(method, path);
        return stubService.listBySpace(spaceId).stream()
                .filter(s -> want.equals(MockRuleKeys.of(s.method(), s.pathPattern())))
                .map(com.sixbean.testpike.core.mock.api.dto.MockStubInfo::id)
                .findFirst().orElseThrow(() -> new AssertionError("no rule for " + want));
    }

    @Test
    void planSplitsEndpointDriftIntoThreeStates() {
        long projectId = newProject();
        String name = "契约升级-" + UUID.randomUUID().toString().substring(0, 8);
        long v1 = uploadSpec(projectId, name, V1_JSON);
        long spaceId = newSpace(projectId);
        spaceIdForImportedContract(projectId, v1, spaceId);
        long v2 = uploadSpec(projectId, name, V2_JSON);

        SpecUpgradePlan plan = upgradeService.planDiff(spaceId, v2);

        assertTrue(plan.hasPrevious());
        assertEquals(List.of("GET /shipments"), plan.addedDrafts().stream()
                .map(d -> MockRuleKeys.of(d.method(), d.pathPattern())).toList());
        assertEquals("spec:" + v2, plan.addedDrafts().get(0).sourceRef());
        assertEquals(MockStubDraft.SOURCE_OPENAPI, plan.addedDrafts().get(0).source());
        assertEquals(List.of(stubIdOf(spaceId, "POST", "/orders")), plan.changedStubIds());
        assertEquals(List.of(stubIdOf(spaceId, "GET", "/legacy")), plan.removedStubIds());
    }

    @Test
    void firstVersionPlanCarriesNoDifference() {
        long projectId = newProject();
        long v1 = uploadSpec(projectId, "首次契约-" + UUID.randomUUID().toString().substring(0, 8), V1_JSON);
        long spaceId = newSpace(projectId);

        SpecUpgradePlan plan = upgradeService.planDiff(spaceId, v1);

        assertFalse(plan.hasPrevious());
        assertTrue(plan.addedDrafts().isEmpty());
        assertTrue(plan.changedStubIds().isEmpty());
        assertTrue(plan.removedStubIds().isEmpty());
    }

    @Test
    void markingDisablesAndFlagsWithoutDeletingAndReplayFilesNothing() {
        long projectId = newProject();
        String name = "标记契约-" + UUID.randomUUID().toString().substring(0, 8);
        long v1 = uploadSpec(projectId, name, V1_JSON);
        long spaceId = newSpace(projectId);
        spaceIdForImportedContract(projectId, v1, spaceId);
        long v2 = uploadSpec(projectId, name, V2_JSON);
        long legacyId = stubIdOf(spaceId, "GET", "/legacy");
        long keptId = stubIdOf(spaceId, "GET", "/orders/{id}");

        assertEquals(1, upgradeService.markRemovedStale(spaceId, v2));

        var marked = jdbcTemplate.queryForMap(
                "SELECT contract_stale, enabled FROM tp_mock_stub WHERE id = ?", legacyId);
        assertEquals(Boolean.TRUE, marked.get("contract_stale"));
        assertEquals(Boolean.FALSE, marked.get("enabled"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_stub WHERE id = ?", Integer.class, legacyId));

        var kept = jdbcTemplate.queryForMap(
                "SELECT contract_stale, enabled FROM tp_mock_stub WHERE id = ?", keptId);
        assertEquals(Boolean.FALSE, kept.get("contract_stale"));
        assertEquals(Boolean.TRUE, kept.get("enabled"));

        assertEquals(0, upgradeService.markRemovedStale(spaceId, v2));
    }

    @Test
    void markingLeavesHitLedgerUntouched() {
        long projectId = newProject();
        String name = "账本契约-" + UUID.randomUUID().toString().substring(0, 8);
        long v1 = uploadSpec(projectId, name, V1_JSON);
        long spaceId = newSpace(projectId);
        spaceIdForImportedContract(projectId, v1, spaceId);
        long v2 = uploadSpec(projectId, name, V2_JSON);

        long hitsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tp_mock_hit_log", Long.class);
        upgradeService.markRemovedStale(spaceId, v2);
        assertEquals(hitsBefore,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tp_mock_hit_log", Long.class));
    }

    @Test
    void serviceReadPathCarriesTheContractRemovalFlag() {
        long projectId = newProject();
        String name = "读取契约-" + UUID.randomUUID().toString().substring(0, 8);
        long v1 = uploadSpec(projectId, name, V1_JSON);
        long spaceId = newSpace(projectId);
        spaceIdForImportedContract(projectId, v1, spaceId);
        long v2 = uploadSpec(projectId, name, V2_JSON);
        long legacyId = stubIdOf(spaceId, "GET", "/legacy");

        upgradeService.markRemovedStale(spaceId, v2);

        var row = stubService.get(legacyId);
        assertEquals(Boolean.TRUE, row.contractStale());
        assertEquals(Boolean.FALSE, row.enabled());
    }
}

package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.SpecUpgradePlanView;
import com.sixbean.testpike.core.mock.api.dto.SpecUpgradeRow;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockSpecUpgradeService;
import com.sixbean.testpike.core.mock.domain.service.MockStubImportService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal doors of the contract-upgrade difference surface (MJ P6): the plan
 * door must serve the three-state list in one join-ready frame — added rows
 * carry the advice-joined draft shape (wizard default check + one-sentence
 * advice), changed/removed rows carry stub id + method + path so the wizard
 * names the exact rule instead of a bare id list — and the marking door must
 * file an honest count with idempotent replay.
 * <p>All against the real dev database (SpringBootTest, zero mocks —
 * MockSpecUpgradeServiceTest posture, same fixture family).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockSpecUpgradeControllerTest {

    private static final String V1_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Upgrade Door API", "version": "1.0.0"},
              "paths": {
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

    /** v2 gives POST /orders a response property (changed) and drops GET /legacy (removed). */
    private static final String V2_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Upgrade Door API", "version": "2.0.0"},
              "paths": {
                "/orders": {
                  "post": {
                    "operationId": "createOrder",
                    "summary": "创建订单",
                    "responses": {"201": {"description": "created",
                        "content": {"application/json": {"schema": {
                            "type": "object",
                            "properties": {"orderNo": {"type": "string"}}}}}}}
                  }
                }
              }
            }
            """;

    @Autowired
    private MockSpecUpgradeController controller;

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
        String code = "mjsd" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "upgd-" + UUID.randomUUID().toString().substring(0, 8);
        return spaceService.create(projectId, new MockSpaceInput(
                "Door space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    private long uploadSpec(long projectId, String name, String json) {
        ApiSpecInfo info = apiSpecService.upload(1L, projectId, name,
                json.getBytes(StandardCharsets.UTF_8), null);
        return info.id();
    }

    @Test
    void planDoorServesThreeStateRowsWithMethodAndPath() {
        long projectId = newProject();
        String name = "契约升级门-端点漂移-" + UUID.randomUUID().toString().substring(0, 8);
        long v1 = uploadSpec(projectId, name, V1_JSON);
        long spaceId = newSpace(projectId);
        importService.importDrafts(spaceId, importService.previewDrafts(v1));
        long v2 = uploadSpec(projectId, name, V2_JSON);

        SpecUpgradePlanView view = controller.plan(spaceId, v2).getData();

        assertNotNull(view);
        assertTrue(view.hasPrevious());
        // Changed rows name the exact rule: id + verb + path (wizard step needs
        // no second read to render the row).
        List<SpecUpgradeRow> changed = view.changed();
        assertEquals(1, changed.size());
        MockStubInfo orders = stubService.get(changed.get(0).stubId());
        assertEquals("POST", orders.method());
        assertEquals("/orders", orders.pathPattern());
        assertEquals(changed.get(0).method(), orders.method());
        assertEquals(changed.get(0).pathPattern(), orders.pathPattern());
        List<SpecUpgradeRow> removed = view.removed();
        assertEquals(1, removed.size());
        MockStubInfo legacy = stubService.get(removed.get(0).stubId());
        assertEquals("GET", removed.get(0).method());
        assertEquals("GET", legacy.method());
        assertEquals("/legacy", removed.get(0).pathPattern());
    }

    @Test
    void addedRowsArriveAdviceJoinedForTheWizardFrame() {
        long projectId = newProject();
        String name = "契约升级门-建议联查-" + UUID.randomUUID().toString().substring(0, 8);
        long v1 = uploadSpec(projectId, name, V1_JSON);
        long spaceId = newSpace(projectId);
        importService.importDrafts(spaceId, importService.previewDrafts(v1));
        // A rule the space serves for hand, not from the contract: the advice
        // floor must see it when judging the added row that collides with it.
        stubService.create(spaceId, new MockStubInput("手工发货规则", 100, "GET", "exact",
                "/shipments", "[]", "{\"status\":200}", null, null, null,
                "manual", "hand-anchor", true));

        long v2 = uploadSpec(projectId, name, """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "Upgrade Door API", "version": "3.0.0"},
                  "paths": {
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
                """);

        SpecUpgradePlanView view = controller.plan(spaceId, v2).getData();

        // v3 vs v2 (chain walks upward): added = /shipments only if the planner
        // compares against v2; the added row must carry the advice join shape.
        assertTrue(view.added().size() >= 1);
        view.added().forEach(row -> {
            assertNotNull(row.verdict(), "added rows carry a recommend/avoid verdict");
            assertNotNull(row.reason(), "added rows carry the one-sentence advice");
            assertNotNull(row.adviceState());
        });
    }

    @Test
    void markRemovedDoorFilesHonestCountAndReplayFilesZero() {
        long projectId = newProject();
        String name = "契约升级门-停用标记-" + UUID.randomUUID().toString().substring(0, 8);
        long v1 = uploadSpec(projectId, name, V1_JSON);
        long spaceId = newSpace(projectId);
        importService.importDrafts(spaceId, importService.previewDrafts(v1));
        long v2 = uploadSpec(projectId, name, V2_JSON);

        int marked = controller.markRemoved(spaceId, v2).getData();
        assertEquals(1, marked);
        assertEquals(0, controller.markRemoved(spaceId, v2).getData(),
                "replaying the marking door must change nothing");
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
                "SELECT contract_stale FROM tp_mock_stub WHERE path_pattern = '/legacy' AND space_id = ?",
                Boolean.class, spaceId));
    }

    @Test
    void firstVersionDoorReportsNoPreviousHonestly() {
        long projectId = newProject();
        long v1 = uploadSpec(projectId,
                "首次契约门-" + UUID.randomUUID().toString().substring(0, 8), V1_JSON);
        long spaceId = newSpace(projectId);

        SpecUpgradePlanView view = controller.plan(spaceId, v1).getData();

        assertTrue(!view.hasPrevious());
        assertTrue(view.added().isEmpty());
        assertTrue(view.changed().isEmpty());
        assertTrue(view.removed().isEmpty());
    }
}

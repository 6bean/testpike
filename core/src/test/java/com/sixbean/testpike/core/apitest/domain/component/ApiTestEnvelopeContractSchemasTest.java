package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api-test-run envelope trailing 'contractSchemas' key , core
 * assembly side).
 * <p>Contract (03 trailing-key style): a request whose assertion group carries a
 * schema row in the contract dialect (type=schema, via=contract) gets the folded
 * endpoint's response schemas pinned from the newest contract snapshot
 * (tp_api_spec, per-name max version, folded-key equality through the spec-domain
 * lookup). The executor never reverse-queries the DB.
 * <p>Zero-drift spectrum: inline-only rows, non-schema rows, or a contract
 * dialect with no covering snapshot = the key must NOT appear at all — the old
 * envelope shape stays byte-identical and the executor fails closed by name
 * (never a silently-passing schema assertion).
 * <p>Real dev database, zero mocks; fixture-owned project cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiTestEnvelopeContractSchemasTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ApiRunService apiRunService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contract_dialect_row_pins_latest_snapshot_schemas() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix,
                "[{\"type\":\"schema\",\"via\":\"contract\"}]");
        try {
            // v1 then v2 of the same contract name: the newest version must win
            insertSpec(f.projectId, "orders-api", 1,
                    specEndpoints("{\"200\":\"{\\\"type\\\":\\\"object\\\",\\\"required\\\":[\\\"staleFlag\\\"]}\"}"));
            insertSpec(f.projectId, "orders-api", 2,
                    specEndpoints("{\"200\":\"{\\\"type\\\":\\\"object\\\",\\\"required\\\":[\\\"data\\\"]}\","
                            + "\"404\":\"{\\\"type\\\":\\\"object\\\"}\"}"));

            apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            String actionJson = latestEnvelopeJson(f.projectId);
            JsonNode env = MAPPER.readTree(actionJson);

            JsonNode schemas = env.path("contractSchemas");
            assertTrue(schemas.isObject(),
                    "contract dialect + covering snapshot must pin the key, got: " + env);
            assertEquals(2, schemas.size(), "both declared codes ride the pin");
            assertTrue(schemas.path("200").asText().contains("data"),
                    "newest version wins (v2 carries data, v1 carried staleFlag): "
                            + schemas.path("200").asText());
            // old-shape keys stay intact alongside the trailing key
            assertEquals("api-test-run", env.path("kind").asText());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void inline_only_assertions_emit_no_contract_schemas_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix,
                "[{\"type\":\"schema\",\"via\":\"inline\",\"schema\":{\"type\":\"object\"}}]");
        try {
            insertSpec(f.projectId, "orders-api", 1,
                    specEndpoints("{\"200\":\"{\\\"type\\\":\\\"object\\\"}\"}"));
            apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            assertTrue(env.path("contractSchemas").isMissingNode(),
                    "inline dialect needs no pinning = old shape untouched, got: " + env);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void contract_dialect_without_snapshot_emits_no_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix,
                "[{\"type\":\"status\",\"expect\":200},"
                        + "{\"type\":\"schema\",\"via\":\"contract\",\"code\":200}]");
        try {
            apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            assertTrue(env.path("contractSchemas").isMissingNode(),
                    "no covering contract = no empty pin; executor fails closed by name, got: " + env);
        } finally {
            cleanup(f);
        }
    }

    // ---- fixtures -------------------------------------------------------

    private record Fixture(long projectId, long envId, long collectionId, long requestId) {
    }

    private Fixture createFixture(String suffix, String assertionsJson) {
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "t372cs-" + suffix, "t372cs" + suffix);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, "t372cs" + suffix);
        long envId = jdbc.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url, "
                        + "config_json) VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + suffix, "http://order-" + projectId + ".test");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "契约schema域-" + suffix, null));
        // Path folds to the same match key as the snapshot's {id} template below.
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(root.id(), "查订单",
                "GET", "/api/v1/orders/123", "[]", null, null, assertionsJson));
        return new Fixture(projectId, envId, root.id(), req.id());
    }

    /** endpoints JSONB snapshot with one endpoint covering GET /api/v1/orders/{id}. */
    private String specEndpoints(String responseSchemasJson) {
        return "[{\"method\":\"GET\",\"path\":\"/api/v1/orders/{id}\","
                + "\"endpointKey\":\"GET /api/v1/orders/{id}\",\"operationId\":\"getOrder\","
                + "\"title\":\"查订单\",\"parameters\":[],\"responseSchemas\":"
                + responseSchemasJson + "}]";
    }

    private void insertSpec(long projectId, String name, int version, String endpointsJson) {
        jdbc.update("INSERT INTO tp_api_spec (project_id, name, spec_format, spec_text, "
                        + "content_sha256, spec_version, endpoints) "
                        + "VALUES (?, ?, 'openapi3-json', '{}', ?, ?, ?::jsonb)",
                projectId, name, DigestHexProbe.sha64(name + version), version, endpointsJson);
    }

    /** 64-char filler for the NOT NULL content_sha256 column (fixture-only value). */
    private static final class DigestHexProbe {
        static String sha64(String seed) {
            String hex = UUID.nameUUIDFromBytes(seed.getBytes()).toString().replace("-", "");
            return (hex + hex).substring(0, 64);
        }
    }

    private String latestEnvelopeJson(long projectId) {
        return jdbc.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                String.class, projectId);
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_apitest_run WHERE request_id IN "
                + "(SELECT id FROM tp_apitest_request WHERE collection_id = ?)", f.collectionId());
        jdbc.update("DELETE FROM tp_api_spec WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_apitest_request WHERE collection_id = ?", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_collection WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project_env WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_audit_log WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project WHERE id = ?", f.projectId());
    }
}

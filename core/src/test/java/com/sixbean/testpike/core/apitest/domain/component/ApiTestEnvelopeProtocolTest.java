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
 * api-test-run envelope transport discriminator part 2, core
 * assembly side).
 * <p>Contract (03 trailing-key style): a request row whose saved protocol is
 * {@code ws} gets a {@code protocol:"ws"} key in the dispatch envelope so the
 * executor can branch onto the WebSocket consumption path. Every other transport
 * (http rows and legacy rows carrying blank/null) emits NO key at all — the old
 * envelope shape stays byte-identical and the executor's default branch keeps
 * running exactly as before (zero-drift rule of every trailing key on this line).
 * <p>Real dev database, zero mocks; fixture-owned project cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiTestEnvelopeProtocolTest {

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
    void ws_request_envelope_carries_protocol_ws_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix, "ws");
        try {
            apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            assertEquals("ws", env.path("protocol").asText(),
                    "a ws row must stamp the transport discriminator, got: " + env);
            // the rest of the envelope is untouched by the trailing key
            assertEquals("api-test-run", env.path("kind").asText());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void http_request_envelope_emits_no_protocol_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix, "http");
        try {
            apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            assertTrue(env.path("protocol").isMissingNode(),
                    "http rows keep the old envelope shape byte-identical, got: " + env);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void legacy_blank_protocol_row_emits_no_protocol_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix, null);
        try {
            // force a raw blank into the column (bypasses the save-gate normalizer;
            // simulates a row written before the column existed = zero backfill case)
            jdbc.update("UPDATE tp_apitest_request SET protocol = '' WHERE id = ?", f.requestId());
            apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            assertTrue(env.path("protocol").isMissingNode(),
                    "blank protocol normalizes to the http default = no key, got: " + env);
        } finally {
            cleanup(f);
        }
    }

    // ---- fixtures -------------------------------------------------------

    private record Fixture(long projectId, long envId, long collectionId, long requestId) {
    }

    private Fixture createFixture(String suffix, String protocol) {
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "t372proto-" + suffix, "t372pt" + suffix);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, "t372pt" + suffix);
        long envId = jdbc.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url, "
                        + "config_json) VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + suffix, "http://ws-" + projectId + ".test");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "协议判别域-" + suffix, null));
        // Assertion rows must match the transport face (cross-grammar save gate):
        // ws rows only accept ws.* types, so the ws fixture carries ws.receive;
        // http/legacy rows keep the plain status row untouched.
        String assertions = "ws".equals(protocol)
                ? "[{\"type\":\"ws.receive\",\"expect\":\"pong\"}]"
                : "[{\"type\":\"status\",\"expect\":200}]";
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(root.id(), "查订单",
                "GET", "/api/v1/orders/123", "[]", null, null,
                assertions, null, null, protocol));
        return new Fixture(projectId, envId, root.id(), req.id());
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
        jdbc.update("DELETE FROM tp_apitest_request WHERE collection_id = ?", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_collection WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project_env WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_audit_log WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project WHERE id = ?", f.projectId());
    }
}

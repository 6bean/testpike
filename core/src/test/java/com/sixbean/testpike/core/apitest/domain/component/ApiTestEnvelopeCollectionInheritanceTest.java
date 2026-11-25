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
 * api-test-run envelope collection-level inheritance (dispatch merge single
 * point, assembly side).
 * <p>Contract: headers merge per key case-insensitively — collection defaults
 * fill in, a request-level row with the same name wins and keeps its own casing.
 * auth replaces as a whole block: a request without its own auth inherits the
 * collection block; a request explicitly declaring {"type":"none"} opts out and
 * must never fall back to the collection default (invisible inheritance is the
 * exact failure this face exists to prevent).
 * <p>Zero-drift: a legacy collection (headers '[]', auth '{}') keeps the old
 * envelope shape byte-for-byte — no key changes, no new keys.
 * <p>Real dev database, zero mocks; fixture-owned project cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiTestEnvelopeCollectionInheritanceTest {

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
    void collection_default_headers_merge_under_request_same_name_wins() throws Exception {
        Fixture f = createFixture("inh1");
        try {
            setCollectionDefaults(f.collectionId,
                    "[{\"name\":\"X-Env\",\"value\":\"from-collection\"},"
                            + "{\"name\":\"X-Trace\",\"value\":\"t1\"}]", "{}");
            long requestId = newRequest(f, "[{\"name\":\"x-env\",\"value\":\"from-request\"}]", "{}");
            apiRunService.create(1L, new RunCreateInput(requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            JsonNode headers = env.path("headers");
            assertEquals("from-request", headerValueIgnoreCase(headers, "x-env"),
                    "request-level same-name header must win, got: " + headers);
            // the winner keeps the request row identity (one entry, no case twin)
            assertEquals(1, countIgnoreCase(headers, "x-env"),
                    "case-insensitive merge must not leave a duplicate twin: " + headers);
            assertEquals("t1", headerValueIgnoreCase(headers, "X-Trace"),
                    "collection-only header must be inherited, got: " + headers);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void request_without_auth_inherits_collection_auth_block() throws Exception {
        Fixture f = createFixture("inh2");
        try {
            setCollectionDefaults(f.collectionId, "[]",
                    "{\"type\":\"bearer\",\"token\":\"col-token\"}");
            long requestId = newRequest(f, "[]", "{}");
            apiRunService.create(1L, new RunCreateInput(requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            JsonNode auth = env.path("auth");
            assertEquals("bearer", auth.path("type").asText(),
                    "an auth-less request must inherit the collection block, got: " + env);
            assertEquals("col-token", auth.path("token").asText(),
                    "inherited auth must carry the collection values, got: " + auth);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void explicit_none_auth_never_falls_back_to_collection_default() throws Exception {
        Fixture f = createFixture("inh3");
        try {
            setCollectionDefaults(f.collectionId, "[]",
                    "{\"type\":\"bearer\",\"token\":\"col-token\"}");
            long requestId = newRequest(f, "[]", "{\"type\":\"none\"}");
            apiRunService.create(1L, new RunCreateInput(requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            JsonNode auth = env.path("auth");
            assertTrue(auth.isMissingNode() || auth.isEmpty()
                            || !"bearer".equals(auth.path("type").asText()),
                    "explicit none must opt the request out of collection auth, got: " + env);
            assertTrue(!env.toString().contains("col-token"),
                    "opted-out request must not leak the collection token, got: " + env);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void legacy_empty_collection_defaults_keep_old_envelope_shape() throws Exception {
        Fixture f = createFixture("inh4");
        try {
            // columns already default to '[]' / '{}' — do not touch them
            long requestId = newRequest(f, "[{\"name\":\"X-A\",\"value\":\"1\"}]", "{}");
            apiRunService.create(1L, new RunCreateInput(requestId, f.envId));
            JsonNode env = MAPPER.readTree(latestEnvelopeJson(f.projectId));
            JsonNode headers = env.path("headers");
            assertEquals(1, headers.size(), "no inherited keys may appear, got: " + headers);
            assertEquals("1", headers.path("X-A").asText());
            assertTrue(env.path("auth").isEmpty(),
                    "empty request auth + empty collection auth stays empty, got: " + env);
        } finally {
            cleanup(f);
        }
    }

    // ---- fixtures -------------------------------------------------------

    private record Fixture(long projectId, long envId, long collectionId) {
    }

    private Fixture createFixture(String tag) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = tag + suffix;
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "t412-" + code, code);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        long envId = jdbc.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url, "
                        + "config_json) VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + code, "http://inh-" + projectId + ".test");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "继承域-" + code, null));
        return new Fixture(projectId, envId, root.id());
    }

    private void setCollectionDefaults(long collectionId, String headersJson, String authJson) {
        jdbc.update("UPDATE tp_apitest_collection SET headers_json = ?::jsonb,"
                + " auth_json = ?::jsonb WHERE id = ?", headersJson, authJson, collectionId);
    }

    private long newRequest(Fixture f, String headersJson, String authJson) {
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(
                f.collectionId(), "继承请求", "GET", "/api/v1/orders",
                headersJson, null, authJson, "[{\"type\":\"status\",\"expect\":200}]"));
        return req.id();
    }

    private String latestEnvelopeJson(long projectId) {
        return jdbc.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                String.class, projectId);
    }

    private static String headerValueIgnoreCase(JsonNode headers, String name) {
        java.util.Iterator<String> it = headers.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (key.equalsIgnoreCase(name)) {
                return headers.path(key).asText();
            }
        }
        return null;
    }

    private static int countIgnoreCase(JsonNode headers, String name) {
        int n = 0;
        java.util.Iterator<String> it = headers.fieldNames();
        while (it.hasNext()) {
            if (it.next().equalsIgnoreCase(name)) {
                n++;
            }
        }
        return n;
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_apitest_run WHERE request_id IN "
                + "(SELECT id FROM tp_apitest_request WHERE collection_id = ?)", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_request WHERE collection_id = ?", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_collection WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project_env WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", f.projectId());
// fixture audit rows stay: mid-range ledger deletion forges chain breaks
        jdbc.update("DELETE FROM tp_project WHERE id = ?", f.projectId());
    }
}

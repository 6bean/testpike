package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api-test-run envelope trailing 'mock' key .
 * <p>Contract (03 trailing-key style): a run whose promoted case declares a
 * mock-space binding AND that space is PUBLISHED (status=1) gets
 * mock={spaceKey, baseUrl, snapshotVersion} appended to the dispatch envelope.
 * baseUrl = K8 mock.public-base-url + mock.traffic.base-path + "/" + spaceKey
 * (same two keys and defaults the admin serving-url bridge reads — one source of
 * truth, no second copy); snapshotVersion is the content pin read through
 * MockSpaceService.snapshot (pinOf single source, never recomputed here).
 * <p>Zero-drift spectrum: no binding, binding to a draft/retired space, or an
 * already-deleted case = the key must NOT appear at all — an unbound run keeps
 * the old envelope shape byte-for-byte (the executor only rewrites env_base
 * when the key exists).
 * <p>Real dev database, zero mocks; fixture-owned project cleaned in finally
 * (no table truncation — sibling suites own those tables).
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiTestEnvelopeMockKeyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ApiRunService apiRunService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private MockSpaceService mockSpaceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void published_space_binding_appends_mock_key_with_pinned_version() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix);
        String oldPublic = configCacheHolder.get("mock.public-base-url");
        String oldPath = configCacheHolder.get("mock.traffic.base-path");
        try {
            configCacheHolder.put("mock.public-base-url", "http://mock.example:18086");
            configCacheHolder.put("mock.traffic.base-path", "/mocks");
            long caseId = insertBoundCase(f.projectId, f.spaceId);
            promoteRequest(f.requestId, caseId);
            publishSpace(f.spaceId);

            ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            String actionJson = jdbc.queryForObject(
                    "SELECT action_json FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, f.projectId);
            JsonNode env = MAPPER.readTree(actionJson);

            JsonNode mock = env.path("mock");
            assertTrue(mock.isObject(), "published binding must append the mock key, got: " + env);
            assertEquals(f.spaceKey, mock.path("spaceKey").asText());
            assertEquals("http://mock.example:18086/mocks/" + f.spaceKey,
                    mock.path("baseUrl").asText(),
                    "baseUrl = public base + traffic prefix + space key");
            MockRuleSnapshotView snapshot = mockSpaceService.snapshot(f.spaceKey);
            assertEquals(snapshot.version(), mock.path("snapshotVersion").asText(),
                    "version pin must come from the snapshot service, never a second computation");
            // old-shape keys stay intact alongside the trailing key (zero-drift discipline)
            assertEquals("api-test-run", env.path("kind").asText());
            assertEquals("http://order-" + f.projectId + ".test", env.path("envBaseUrl").asText(),
                    "envBaseUrl keeps the authoritative env URL (rewrite belongs to the executor)");
        } finally {
            restore(oldPublic, oldPath);
            cleanup(f);
        }
    }

    @Test
    void unbound_request_emits_no_mock_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix);
        try {
            ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            String actionJson = jdbc.queryForObject(
                    "SELECT action_json FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, f.projectId);
            JsonNode env = MAPPER.readTree(actionJson);
            assertTrue(run.id() > 0);
            assertTrue(env.path("mock").isMissingNode(),
                    "no promoted-case binding = no mock key (old shape untouched), got: " + env);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void draft_space_binding_emits_no_mock_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix);
        try {
            long caseId = insertBoundCase(f.projectId, f.spaceId);
            promoteRequest(f.requestId, caseId);
            // space stays draft (status=0): nothing may be served, so no key either
            ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            String actionJson = jdbc.queryForObject(
                    "SELECT action_json FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, f.projectId);
            JsonNode env = MAPPER.readTree(actionJson);
            assertTrue(run.id() > 0);
            assertTrue(env.path("mock").isMissingNode(),
                    "draft (non-published) space = no mock key, got: " + env);
            assertEquals("http://order-" + f.projectId + ".test", env.path("envBaseUrl").asText());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void deleted_case_binding_emits_no_mock_key() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Fixture f = createFixture(suffix);
        try {
            long caseId = insertBoundCase(f.projectId, f.spaceId);
            promoteRequest(f.requestId, caseId);
            publishSpace(f.spaceId);
            jdbc.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", caseId);

            ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(f.requestId, f.envId));
            String actionJson = jdbc.queryForObject(
                    "SELECT action_json FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, f.projectId);
            JsonNode env = MAPPER.readTree(actionJson);
            assertTrue(run.id() > 0);
            assertTrue(env.path("mock").isMissingNode(),
                    "binding through an already-deleted case = no mock key, got: " + env);
        } finally {
            cleanup(f);
        }
    }

    // ---- fixtures -------------------------------------------------------

    private record Fixture(long projectId, long envId, long collectionId, long requestId,
                           long spaceId, String spaceKey) {
    }

    private Fixture createFixture(String suffix) {
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "t364env-" + suffix, "t364e" + suffix);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, "t364e" + suffix);
        long envId = jdbc.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url, "
                        + "config_json) VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + suffix, "http://order-" + projectId + ".test");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "仿真域-" + suffix, null));
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(root.id(), "查订单",
                "GET", "/api/v1/orders/{id}", "[]", null, null,
                "[{\"type\":\"status\",\"expect\":200}]"));
        String spaceKey = "t364env" + suffix;
        long spaceId = jdbc.queryForObject("INSERT INTO tp_mock_space (project_id, name, space_key, "
                        + "status) VALUES (?, ?, ?, 0) RETURNING id",
                Long.class, projectId, "t364env-" + suffix, spaceKey);
        return new Fixture(projectId, envId, root.id(), req.id(), spaceId, spaceKey);
    }

    private long insertBoundCase(long projectId, long spaceId) {
        return jdbc.queryForObject("INSERT INTO tp_test_case (case_no, project_id, title, "
                        + "mock_space_id) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "T364ENV-" + UUID.randomUUID().toString().substring(0, 20),
                projectId, "mock-bound case", spaceId);
    }

    private void promoteRequest(long requestId, long caseId) {
        jdbc.update("UPDATE tp_apitest_request SET promoted_case_id = ? WHERE id = ?",
                caseId, requestId);
    }

    private void publishSpace(long spaceId) {
        jdbc.update("UPDATE tp_mock_space SET status = 1 WHERE id = ?", spaceId);
    }

    private void restore(String oldPublic, String oldPath) {
        if (oldPublic == null) {
            configCacheHolder.remove("mock.public-base-url");
        } else {
            configCacheHolder.put("mock.public-base-url", oldPublic);
        }
        if (oldPath == null) {
            configCacheHolder.remove("mock.traffic.base-path");
        } else {
            configCacheHolder.put("mock.traffic.base-path", oldPath);
        }
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", f.projectId());
        // FK-safe order: clear promoted_case_id links before dropping cases/space
        jdbc.update("UPDATE tp_apitest_request SET promoted_case_id = NULL WHERE collection_id = ?",
                f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_run WHERE request_id IN "
                + "(SELECT id FROM tp_apitest_request WHERE collection_id = ?)", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_request WHERE collection_id = ?", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_collection WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_mock_hit_log WHERE space_id = ?", f.spaceId());
        jdbc.update("DELETE FROM tp_test_case WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_mock_space WHERE id = ?", f.spaceId());
        jdbc.update("DELETE FROM tp_project_env WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", f.projectId());
// fixture audit rows stay: mid-range ledger deletion forges chain breaks
        jdbc.update("DELETE FROM tp_project WHERE id = ?", f.projectId());
    }
}

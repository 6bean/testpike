package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiEffectiveHeader;
import com.sixbean.testpike.core.apitest.api.dto.ApiEffectivePreview;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effective-defaults preview face : the request editor must show
 * what will actually go on the wire and where each piece came from — the same
 * verdict the dispatch envelope uses, read through one resolver instead of a
 * second interpretation living in the front end.
 * <p>Contract: merged headers carry a per-key source (request rows win a
 * case-insensitive name collision and keep their own casing; collection
 * defaults only fill keys nobody else carries). auth resolves as a whole block
 * with a four-valued source — request (own block), collection (inherited),
 * none-explicit (the request opted out with {"type":"none"} and must never
 * fall back), none (nothing declared anywhere).
 * <p>Real dev database, zero mocks; fixture-owned project cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiRequestEffectivePreviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void inherited_headers_report_collection_source_and_request_wins_same_name() throws Exception {
        Fixture f = createFixture("prev1");
        try {
            setCollectionDefaults(f.collectionId(),
                    "[{\"name\":\"X-Env\",\"value\":\"from-collection\"},"
                            + "{\"name\":\"X-Trace\",\"value\":\"t1\"}]", "{}");
            long requestId = newRequest(f,
                    "[{\"name\":\"x-env\",\"value\":\"from-request\"}]", "{}");
            ApiEffectivePreview p = apiRequestService.effectivePreview(requestId);
            assertEquals("x-env", headerNamed(p.headers(), "x-env").name(),
                    "the request row keeps its own casing");
            assertEquals("from-request", headerNamed(p.headers(), "x-env").value());
            assertEquals("request", headerNamed(p.headers(), "x-env").source());
            assertEquals("collection", headerNamed(p.headers(), "X-Trace").source());
            // no case twin may survive the merge
            assertEquals(1, countNamed(p.headers(), "x-env"),
                    "case-insensitive merge must leave exactly one entry");
            JsonNode merged = MAPPER.readTree(p.headersJson());
            assertEquals("from-request", merged.path("x-env").asText());
            assertEquals("t1", merged.path("X-Trace").asText());
            assertEquals("none", p.authSource());
            assertEquals(f.collectionName(), p.collectionName());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void auth_less_request_previews_the_collection_block_with_source() throws Exception {
        Fixture f = createFixture("prev2");
        try {
            setCollectionDefaults(f.collectionId(), "[]",
                    "{\"type\":\"bearer\",\"token\":\"col-token\"}");
            long requestId = newRequest(f, "[]", "{}");
            ApiEffectivePreview p = apiRequestService.effectivePreview(requestId);
            assertEquals("collection", p.authSource());
            JsonNode auth = MAPPER.readTree(p.authJson());
            assertEquals("bearer", auth.path("type").asText());
            assertEquals("col-token", auth.path("token").asText());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void explicit_none_auth_previews_as_opt_out_and_never_falls_back() throws Exception {
        Fixture f = createFixture("prev3");
        try {
            setCollectionDefaults(f.collectionId(), "[]",
                    "{\"type\":\"bearer\",\"token\":\"col-token\"}");
            long requestId = newRequest(f, "[]", "{\"type\":\"none\"}");
            ApiEffectivePreview p = apiRequestService.effectivePreview(requestId);
            assertEquals("none-explicit", p.authSource(),
                    "the opt-out must be visible as its own verdict, got: " + p.authSource());
            assertFalse(p.authJson().contains("col-token"),
                    "an opted-out preview must not leak the collection block: " + p.authJson());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void request_own_auth_block_previews_with_request_source() throws Exception {
        Fixture f = createFixture("prev4");
        try {
            setCollectionDefaults(f.collectionId(), "[]",
                    "{\"type\":\"basic\",\"username\":\"col\",\"password\":\"@cred/col_pw\"}");
            // secret slots must carry vault references (save gate) — the preview
            // shows them as stored, never the resolved value
            long requestId = newRequest(f, "[]",
                    "{\"type\":\"bearer\",\"token\":\"@cred/own_token\"}");
            ApiEffectivePreview p = apiRequestService.effectivePreview(requestId);
            assertEquals("request", p.authSource());
            JsonNode auth = MAPPER.readTree(p.authJson());
            assertEquals("bearer", auth.path("type").asText());
            assertEquals("@cred/own_token", auth.path("token").asText());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void empty_collection_defaults_preview_only_request_rows() throws Exception {
        Fixture f = createFixture("prev5");
        try {
            // columns already default to '[]' / '{}' — nothing to inherit
            long requestId = newRequest(f,
                    "[{\"name\":\"X-A\",\"value\":\"1\"}]", "{}");
            ApiEffectivePreview p = apiRequestService.effectivePreview(requestId);
            assertEquals(1, p.headers().size());
            assertEquals("request", p.headers().get(0).source());
            assertEquals("none", p.authSource());
            assertTrue(MAPPER.readTree(p.authJson()).isEmpty());
        } finally {
            cleanup(f);
        }
    }

    // ---- fixtures -------------------------------------------------------

    private record Fixture(long projectId, long collectionId, String collectionName) {
    }

    private Fixture createFixture(String tag) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = tag + suffix;
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "t412-" + code, code);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        String name = "生效预览域-" + code;
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, name, null));
        return new Fixture(projectId, root.id(), name);
    }

    private void setCollectionDefaults(long collectionId, String headersJson, String authJson) {
        jdbc.update("UPDATE tp_apitest_collection SET headers_json = ?::jsonb,"
                + " auth_json = ?::jsonb WHERE id = ?", headersJson, authJson, collectionId);
    }

    private long newRequest(Fixture f, String headersJson, String authJson) {
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(
                f.collectionId(), "预览请求", "GET", "/api/v1/orders",
                headersJson, null, authJson, "[{\"type\":\"status\",\"expect\":200}]"));
        return req.id();
    }

    private static ApiEffectiveHeader headerNamed(java.util.List<ApiEffectiveHeader> rows,
                                                  String name) {
        return rows.stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no header " + name + " in " + rows));
    }

    private static int countNamed(java.util.List<ApiEffectiveHeader> rows, String name) {
        return (int) rows.stream().filter(h -> h.name().equalsIgnoreCase(name)).count();
    }

    private void cleanup(Fixture f) {
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

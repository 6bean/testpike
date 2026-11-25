package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiEffectivePreview;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch effective-defaults preview for a whole collection: importing an entire
 * interface set into a perf scenario needs per-request merged headers/auth in
 * ONE round trip — the per-request preview face would mean 500 HTTP calls at
 * worst scale.
 * <p>Contract: the batch face is row-for-row identical to the single-request
 * preview (same resolver verdict, zero second interpretation — red line 6),
 * ordered by the user-maintained member order, and an unknown collection is a
 * 404 with a human message instead of a silent empty list.
 * <p>Real dev database, zero mocks; fixture-owned project cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiRequestEffectivePreviewBatchTest {

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void batch_rows_are_identical_to_the_single_request_preview_face() throws Exception {
        Fixture f = createFixture("bat1");
        try {
            setCollectionDefaults(f.collectionId(),
                    "[{\"name\":\"X-Env\",\"value\":\"from-collection\"},"
                            + "{\"name\":\"X-Trace\",\"value\":\"t1\"}]",
                    "{\"type\":\"bearer\",\"token\":\"@cred/col_token\"}");
            // row 1: inherits auth, wins a header name case-insensitively
            long r1 = newRequest(f, "[{\"name\":\"x-env\",\"value\":\"from-request\"}]", "{}");
            // row 2: explicit opt-out of auth, own header rows only
            long r2 = newRequest(f, "[{\"name\":\"X-Own\",\"value\":\"2\"}]",
                    "{\"type\":\"none\"}");
            // row 3: carries its own auth block (save gate: password slot must
            // carry a vault reference, never a plain value)
            long r3 = newRequest(f, "[]",
                    "{\"type\":\"basic\",\"username\":\"u\",\"password\":\"@cred/own_pw\"}");
            List<ApiEffectivePreview> batch =
                    apiRequestService.effectivePreviewBatch(f.collectionId());
            assertEquals(List.of(r1, r2, r3), batch.stream().map(ApiEffectivePreview::requestId).toList(),
                    "batch must follow the user-maintained member order");
            for (ApiEffectivePreview row : batch) {
                assertEquals(apiRequestService.effectivePreview(row.requestId()), row,
                        "batch row " + row.requestId() + " diverged from the single preview face");
            }
            assertEquals("collection", batch.get(0).authSource());
            assertEquals("none-explicit", batch.get(1).authSource());
            assertEquals("request", batch.get(2).authSource());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void empty_collection_batches_to_an_empty_list() throws Exception {
        Fixture f = createFixture("bat2");
        try {
            assertTrue(apiRequestService.effectivePreviewBatch(f.collectionId()).isEmpty());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void unknown_collection_is_rejected_with_a_human_not_found() {
        assertThrows(ServiceException.class,
                () -> apiRequestService.effectivePreviewBatch(999_999_999_999L));
    }

    // ---- fixtures (same lineage as ApiRequestEffectivePreviewTest) --------

    private record Fixture(long projectId, long collectionId) {
    }

    private Fixture createFixture(String tag) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = tag + suffix;
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "effbatch-" + code, code);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "批量生效预览集-" + code, null));
        return new Fixture(projectId, root.id());
    }

    private void setCollectionDefaults(long collectionId, String headersJson, String authJson) {
        jdbc.update("UPDATE tp_apitest_collection SET headers_json = ?::jsonb,"
                + " auth_json = ?::jsonb WHERE id = ?", headersJson, authJson, collectionId);
    }

    private long newRequest(Fixture f, String headersJson, String authJson) {
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(
                f.collectionId(), "批量预览请求", "GET", "/api/v1/orders",
                headersJson, null, authJson, "[{\"type\":\"status\",\"expect\":200}]"));
        return req.id();
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

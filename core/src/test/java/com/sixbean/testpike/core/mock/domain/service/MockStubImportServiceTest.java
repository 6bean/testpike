package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-direct import wiring tests : the mapper
 * gets its entry and exit — preview reads the stored endpoint snapshot of a spec row
 * and replays it through StubDraftMapper (parser untouched, zero tokens),
 * import files the drafts through the normal stub save chain so
 * MockRuleValidator stays the single validation point (a hand-broken draft
 * fails with a plain-word 400, never a bypass), and a re-import of the same
 * contract onto the same space skips what is already there instead of
 * double-filing rules (replay idempotence, same posture as the preview
 * apply-chain in the spec domain).
 * <p>All against the real dev database (SpringBootTest, zero mocks —
 * MockRuleServiceTest posture).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockStubImportServiceTest {

    private static final String SPEC_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Import API", "version": "1.0.0"},
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
                  },
                  "trace": {"responses": {"200": {"description": "ok"}}}
                },
                "/orders": {
                  "post": {"summary": "创建订单",
                           "responses": {"201": {"description": "created",
                        "content": {"application/json": {"schema": {"type": "object"}}}}}}
                }
              }
            }
            """;

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
        String code = "t360b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        // through the real save chain (no fixture shortcut past the gates)
        String key = "imp-" + UUID.randomUUID().toString().substring(0, 8);
        return spaceService.create(projectId, new com.sixbean.testpike.core.mock.api.dto.MockSpaceInput(
                "Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    private ApiSpecInfo newSpec(long projectId) {
        return apiSpecService.upload(1L, projectId,
                "契约-" + UUID.randomUUID().toString().substring(0, 8),
                SPEC_JSON.getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    void previewReplaysStoredEndpointSnapshotIntoDrafts() {
        long projectId = newProject();
        ApiSpecInfo spec = newSpec(projectId);

        List<MockStubDraft> drafts = importService.previewDrafts(spec.id());

        // trace is not serveable → only GET /orders/{id} and POST /orders survive
        assertEquals(2, drafts.size());
        MockStubDraft first = drafts.get(0);
        assertEquals("GET", first.method());
        assertEquals("template", first.pathMatchType());
        assertEquals(MockStubDraft.SOURCE_OPENAPI, first.source());
        assertEquals("spec:" + spec.id(), first.sourceRef());
        assertNotNull(first.responseJson());
    }

    @Test
    void previewOfUnknownSpecFailsWithNotFound() {
        assertThrows(ServiceException.class, () -> importService.previewDrafts(999999999L));
    }

    @Test
    void importFilesDraftsThroughTheNormalSaveChain() {
        long projectId = newProject();
        ApiSpecInfo spec = newSpec(projectId);
        long spaceId = newSpace(projectId);

        List<MockStubDraft> drafts = importService.previewDrafts(spec.id());
        MockStubImportResult result = importService.importDrafts(spaceId, drafts);

        assertEquals(2, result.createdStubIds().size());
        assertEquals(0, result.skippedEndpointKeys().size());

        var stubs = stubService.listBySpace(spaceId);
        assertEquals(2, stubs.size());
        assertTrue(stubs.stream().allMatch(s -> "openapi".equals(s.source())));
        assertTrue(stubs.stream().allMatch(s -> ("spec:" + spec.id()).equals(s.sourceRef())));
    }

    @Test
    void reimportOfSameContractSkipsAlreadyFiledRules() {
        long projectId = newProject();
        ApiSpecInfo spec = newSpec(projectId);
        long spaceId = newSpace(projectId);

        List<MockStubDraft> drafts = importService.previewDrafts(spec.id());
        importService.importDrafts(spaceId, drafts);
        MockStubImportResult again = importService.importDrafts(spaceId, drafts);

        assertEquals(0, again.createdStubIds().size());
        assertEquals(2, again.skippedEndpointKeys().size());
        assertEquals(2, stubService.listBySpace(spaceId).size());
    }

    @Test
    void handBrokenDraftIsRefusedByTheValidatorSinglePoint() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        MockStubDraft bad = new MockStubDraft("TRACE /boom", "bad", "TRACE", "exact", "/boom",
                "[]", "{}", MockStubDraft.SOURCE_OPENAPI, "spec:1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> importService.importDrafts(spaceId, List.of(bad)));
        assertFalse(ex.getMessage().isBlank());
        assertEquals(0, stubService.listBySpace(spaceId).size());
    }
}

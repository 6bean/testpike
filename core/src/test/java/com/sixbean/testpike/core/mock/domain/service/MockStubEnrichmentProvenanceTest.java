package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Enrichment provenance closed loop: drafts posted from the bulk contract
 * import carry the AI job identifier that enriched them, the import files
 * that value into tp_mock_stub.enriched_by through the normal save chain,
 * and rows never touched by an enrichment job keep the column NULL (the
 * contract-direct badge stays honest). Re-import of the same keys skips
 * without clobbering the recorded enrichment, and improving an already
 * filed rule goes through the ordinary update gates — the optimistic-lock
 * echo is still required and the validator single point still refuses
 * broken payloads (red line: import and enrichment never add a second
 * write path around the save chain).
 * <p>All against the real dev database (SpringBootTest, zero mocks —
 * MockStubImportServiceTest posture).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockStubEnrichmentProvenanceTest {

    @Autowired
    private MockStubImportService importService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "enrich" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "enr-" + UUID.randomUUID().toString().substring(0, 8);
        return spaceService.create(projectId, new com.sixbean.testpike.core.mock.api.dto.MockSpaceInput(
                "Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    /** Contract-direct draft for a unique path; enrichedBy null = never improved by AI. */
    private MockStubDraft draft(String path, String enrichedBy) {
        String method = "GET";
        if (enrichedBy == null) {
            // compatibility shape: contract mapper drafts built without the enrichment slot
            return new MockStubDraft(method + " " + path, "rule-" + path, method, "exact", path,
                    "[]", "{}", MockStubDraft.SOURCE_OPENAPI, "spec:9001");
        }
        return new MockStubDraft(method + " " + path, "rule-" + path, method, "exact", path,
                "[]", "{}", MockStubDraft.SOURCE_OPENAPI, "spec:9001", enrichedBy);
    }

    private String enrichedByColumn(long stubId) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT enriched_by FROM tp_mock_stub WHERE id = ?", String.class, stubId);
        assertEquals(1, values.size());
        return values.get(0);
    }

    @Test
    void enrichedDraftImportRecordsTheJobAnchorAndKeepsBirthFacts() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);

        MockStubImportResult result = importService.importDrafts(
                spaceId, List.of(draft("/enrich/landed", "job:4242")));

        assertEquals(1, result.createdStubIds().size());
        long stubId = result.createdStubIds().get(0);
        assertEquals("job:4242", enrichedByColumn(stubId));
        MockStubInfo row = stubService.get(stubId);
        assertEquals("openapi", row.source());
        assertEquals("spec:9001", row.sourceRef());
        assertEquals("job:4242", row.enrichedBy());
    }

    @Test
    void contractDirectImportLeavesEnrichmentColumnNull() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);

        MockStubImportResult result = importService.importDrafts(
                spaceId, List.of(draft("/enrich/direct", null)));

        long stubId = result.createdStubIds().get(0);
        assertNull(enrichedByColumn(stubId));
        assertNull(stubService.get(stubId).enrichedBy());
    }

    @Test
    void reimportOfCoveredKeySkipsAndNeverClobbersTheRecordedEnrichment() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        List<MockStubDraft> batch = List.of(draft("/enrich/clobber", "job:777"));

        importService.importDrafts(spaceId, batch);
        MockStubImportResult again = importService.importDrafts(spaceId, batch);

        assertEquals(0, again.createdStubIds().size());
        assertEquals(1, again.skippedEndpointKeys().size());
        long stubId = stubService.listBySpace(spaceId).get(0).id();
        assertEquals("job:777", enrichedByColumn(stubId));
    }

    @Test
    void enrichingAFiledRuleGoesThroughTheVersionedUpdateGate() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long stubId = importService.importDrafts(
                spaceId, List.of(draft("/enrich/upgrade", null))).createdStubIds().get(0);
        MockStubInfo born = stubService.get(stubId);

        // A stale lock echo is refused — the enrichment never bypasses the
        // optimistic gate, it is a normal single-rule edit.
        assertThrows(ServiceException.class, () -> stubService.update(stubId,
                reinput(born, born.version() + 99, "job:99")));
        assertEquals(null, enrichedByColumn(stubId));

        MockStubInfo improved = stubService.update(stubId,
                reinput(born, born.version(), "job:99"));
        assertEquals("job:99", improved.enrichedBy());
        assertEquals("job:99", enrichedByColumn(stubId));
        // Birth facts stay untouched by the enrichment write.
        assertEquals("openapi", improved.source());
        assertEquals("spec:9001", improved.sourceRef());
    }

    private MockStubInput reinput(MockStubInfo row, int expectedVersion, String enrichedBy) {
        return new MockStubInput(row.name(), row.priority(), row.method(), row.pathMatchType(),
                row.pathPattern(), row.matchersJson(), row.responseJson(), row.scenarioName(),
                row.requiredState(), row.newState(), row.source(), row.sourceRef(),
                row.enabled(), expectedVersion, enrichedBy);
    }
}

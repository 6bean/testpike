package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field-level document editing under the row optimistic lock :
 * category changes and the three per-document usage switches are the only
 * editable surfaces; every write must consume the revision the caller read
 * (stale token refused with 40901 naming the current value, missing token
 * refused with 40001 -- the same posture as the plugin/tool edit doors).
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here
 * (by id), never truncates shipped baseline tables.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class DocumentCategoryUsageUpdateTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdProjectId;

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        createdProjectId = projectService.create(1L,
                new ProjectCreateInput("字段编辑项目" + code, code, "zh-CN", "P1", null));
        return createdProjectId;
    }

    private DocumentInfo uploadSpecDocument(long projectId) {
        return documentService.upload(1L, projectId, "editable.md",
                "# 可编辑标题\n正文内容。".getBytes(StandardCharsets.UTF_8), null);
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT doc_category, use_for_extraction, use_in_case_prompt,"
                        + " use_in_grounding, revision FROM tp_document WHERE id = ?", id);
    }

    @AfterEach
    void tearDown() {
        if (createdProjectId != null) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", createdProjectId);
            jdbcTemplate.update("DELETE FROM tp_document WHERE project_id = ?", createdProjectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", createdProjectId);
            createdProjectId = null;
        }
    }

    @Test
    void listCategoryFilterReturnsOnlyMatchingDocuments() {
        // package 3 (screening half): the exact doc_category filter is
        // served server-side over the whole project window; a null filter
        // keeps every category (upload defaults untouched, zero drift).
        long projectId = createProject();
        DocumentInfo reference = documentService.upload(1L, projectId, "reference.md",
                "# 参考资料\n术语与背景说明。".getBytes(StandardCharsets.UTF_8), "REFERENCE");
        DocumentInfo spec = uploadSpecDocument(projectId);
        var refOnly = documentService.list(projectId, null, null, "REFERENCE", 1, 10, null, null);
        assertEquals(1L, refOnly.total());
        assertEquals(reference.id(), refOnly.list().get(0).id());
        assertEquals("REFERENCE", refOnly.list().get(0).category());
        var specOnly = documentService.list(projectId, null, null, "TEST_SPEC", 1, 10, null, null);
        assertEquals(1L, specOnly.total());
        assertEquals(spec.id(), specOnly.list().get(0).id());
        var all = documentService.list(projectId, null, null, null, 1, 10, null, null);
        assertEquals(2L, all.total());
    }

    @Test
    void categoryUpdateWithMatchingRevisionBumpsRevisionAndKeepsSwitches() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        DocumentInfo updated = documentService.updateCategory(1L, doc.id(), "reference", 0);
        assertEquals("REFERENCE", updated.category());
        assertEquals(1, updated.revision());
        // Category edits never rewrite usage switches as a side effect.
        assertTrue(updated.useForExtraction());
        assertTrue(updated.useInCasePrompt());
        assertTrue(updated.useInGrounding());
        Map<String, Object> persisted = row(doc.id());
        assertEquals("REFERENCE", persisted.get("doc_category"));
        assertEquals(1, ((Number) persisted.get("revision")).intValue());
    }

    @Test
    void categoryUpdateWithStaleRevisionIsRejectedNamingCurrentVersion() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        documentService.updateCategory(1L, doc.id(), "DDL_DICT", 0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> documentService.updateCategory(1L, doc.id(), "REFERENCE", 0));
        assertEquals(BizErrorCode.VERSION_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("1"),
                "conflict message must name the current revision, got: " + ex.getMessage());
        // Rejected write leaves the row untouched.
        assertEquals("DDL_DICT", row(doc.id()).get("doc_category"));
    }

    @Test
    void categoryUpdateWithoutRevisionIsRejectedWithReadableReason() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> documentService.updateCategory(1L, doc.id(), "REFERENCE", null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("revision"),
                "message should point at the missing optimistic-lock token, got: "
                        + ex.getMessage());
    }

    @Test
    void categoryUpdateRejectsUnsupportedValueWithReadableValueList() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> documentService.updateCategory(1L, doc.id(), "ARCHIVE", 0));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("REFERENCE"),
                "value list must name supported categories, got: " + ex.getMessage());
    }

    @Test
    void usageSwitchUpdateWithMatchingRevisionPersistsAllThreeAndBumpsRevision() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        DocumentInfo updated = documentService.updateUsageSwitches(1L, doc.id(),
                false, false, true, 0);
        assertFalse(updated.useForExtraction());
        assertFalse(updated.useInCasePrompt());
        assertTrue(updated.useInGrounding());
        assertEquals(1, updated.revision());
        Map<String, Object> persisted = row(doc.id());
        assertEquals(Boolean.FALSE, persisted.get("use_for_extraction"));
        assertEquals(Boolean.FALSE, persisted.get("use_in_case_prompt"));
        assertEquals(Boolean.TRUE, persisted.get("use_in_grounding"));
        assertEquals(1, ((Number) persisted.get("revision")).intValue());
    }

    @Test
    void usageSwitchUpdateWithStaleRevisionIsRejectedNamingCurrentVersion() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        documentService.updateUsageSwitches(1L, doc.id(), false, true, true, 0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> documentService.updateUsageSwitches(1L, doc.id(), true, true, true, 0));
        assertEquals(BizErrorCode.VERSION_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("1"),
                "conflict message must name the current revision, got: " + ex.getMessage());
        assertFalse(row(doc.id()).get("use_for_extraction").equals(true),
                "rejected write must not flip the switch back");
    }

    @Test
    void usageSwitchUpdateWithMissingFieldValueIsRejected() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> documentService.updateUsageSwitches(1L, doc.id(), true, null, true, 0));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("use_in_case_prompt")
                        || ex.getMessage().contains("useInCasePrompt"),
                "message must name the missing switch, got: " + ex.getMessage());
    }

    @Test
    void detailViewExposesUsageSwitchesAndRevision() {
        long projectId = createProject();
        DocumentInfo doc = uploadSpecDocument(projectId);
        documentService.updateUsageSwitches(1L, doc.id(), true, false, false, 0);
        DocumentInfo fresh = documentService.get(doc.id());
        assertNotNull(fresh);
        assertTrue(fresh.useForExtraction());
        assertFalse(fresh.useInCasePrompt());
        assertFalse(fresh.useInGrounding());
        assertEquals(1, fresh.revision());
    }
}

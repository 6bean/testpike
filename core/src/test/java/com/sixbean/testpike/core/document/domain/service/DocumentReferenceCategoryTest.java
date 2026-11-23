package com.sixbean.testpike.core.document.domain.service;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document category value set + per-document usage switches (reference category
 * semantics). Covers: uploading a REFERENCE document turns requirement-extraction
 * participation off while prompt and grounding stay on; uploads without a
 * category keep the legacy all-on defaults (zero drift for existing rows);
 * an unsupported category is refused with a readable value list.
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here
 * (by id), never truncates shipped baseline tables.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class DocumentReferenceCategoryTest {

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
                new ProjectCreateInput("参考类项目" + code, code, "zh-CN", "P1", null));
        return createdProjectId;
    }

    private void cleanup() {
        if (createdProjectId != null) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", createdProjectId);
            jdbcTemplate.update("DELETE FROM tp_document WHERE project_id = ?", createdProjectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", createdProjectId);
            createdProjectId = null;
        }
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void uploadReferenceDocumentTurnsExtractionOffAndKeepsPromptAndGroundingOn() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "glossary.md",
                "# 术语表\n仅参考知识内容，不是需求。".getBytes(StandardCharsets.UTF_8),
                "reference");
        assertNotNull(info);
        assertEquals("REFERENCE", info.category());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT use_for_extraction, use_in_case_prompt, use_in_grounding, revision"
                        + " FROM tp_document WHERE id = ?", info.id());
        assertEquals(Boolean.FALSE, row.get("use_for_extraction"));
        assertEquals(Boolean.TRUE, row.get("use_in_case_prompt"));
        assertEquals(Boolean.TRUE, row.get("use_in_grounding"));
        assertEquals(0, ((Number) row.get("revision")).intValue());
    }

    @Test
    void uploadWithoutCategoryKeepsLegacyUsageDefaults() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "spec.md",
                "# 需求标题\n上传内容正文。".getBytes(StandardCharsets.UTF_8), null);
        assertEquals("TEST_SPEC", info.category());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT use_for_extraction, use_in_case_prompt, use_in_grounding FROM tp_document"
                        + " WHERE id = ?", info.id());
        assertEquals(Boolean.TRUE, row.get("use_for_extraction"));
        assertEquals(Boolean.TRUE, row.get("use_in_case_prompt"));
        assertEquals(Boolean.TRUE, row.get("use_in_grounding"));
    }

    @Test
    void unsupportedCategoryIsRejectedWithReadableValueList() {
        long projectId = createProject();
        ServiceException ex = assertThrows(ServiceException.class, () ->
                documentService.upload(1L, projectId, "any.md",
                        "# 标题\n正文".getBytes(StandardCharsets.UTF_8), "ARCHIVE"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("TEST_SPEC") && msg.contains("DDL_DICT") && msg.contains("REFERENCE"),
                "value list must name all supported categories, got: " + msg);
    }
}

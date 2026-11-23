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
 * Requirement-document category (the source doc that requirement points and
 * cases are derived from). Covers: REQUIREMENT_DOC accepted at upload with
 * extraction participation on by default (same door as TEST_SPEC, never
 * tightened); inline category switching accepts the new value; the rejected
 * value list names all four supported categories; the extraction-participation
 * whitelist ships as a hot-updatable key seeded with a value equivalent to the
 * historical behaviour (TEST_SPEC + DDL_DICT stay on, REFERENCE stays off).
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here
 * (by id), never truncates shipped baseline tables.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class DocumentRequirementDocCategoryTest {

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
                new ProjectCreateInput("需求文档类项目" + code, code, "zh-CN", "P1", null));
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
    void uploadRequirementDocumentTurnsExtractionOnByDefault() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "prd.md",
                "# 需求标题\n上传内容正文。".getBytes(StandardCharsets.UTF_8),
                "REQUIREMENT_DOC");
        assertNotNull(info);
        assertEquals("REQUIREMENT_DOC", info.category());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT use_for_extraction, use_in_case_prompt, use_in_grounding"
                        + " FROM tp_document WHERE id = ?", info.id());
        assertEquals(Boolean.TRUE, row.get("use_for_extraction"),
                "requirement docs are the extraction source -- participation must default on");
        assertEquals(Boolean.TRUE, row.get("use_in_case_prompt"));
        assertEquals(Boolean.TRUE, row.get("use_in_grounding"));
    }

    @Test
    void inlineCategorySwitchAcceptsRequirementDocument() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "legacy.md",
                "# 标题\n正文".getBytes(StandardCharsets.UTF_8), null);
        DocumentInfo switched = documentService.updateCategory(1L, info.id(),
                "REQUIREMENT_DOC", 0);
        assertEquals("REQUIREMENT_DOC", switched.category());
        assertEquals(1, switched.revision());
    }

    @Test
    void rejectedCategoryListNamesAllFourCategories() {
        long projectId = createProject();
        ServiceException ex = assertThrows(ServiceException.class, () ->
                documentService.upload(1L, projectId, "any.md",
                        "# 标题\n正文".getBytes(StandardCharsets.UTF_8), "ARCHIVE"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("TEST_SPEC") && msg.contains("REQUIREMENT_DOC")
                        && msg.contains("DDL_DICT") && msg.contains("REFERENCE"),
                "value list must name all four supported categories, got: " + msg);
        assertTrue(msg.contains("需求文档"),
                "the on-screen name 需求文档 must appear next to REQUIREMENT_DOC, got: " + msg);
    }

    @Test
    void dataDictionaryKeepsExtractionParticipationOn() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "dict.md",
                "# 表结构\n字段清单".getBytes(StandardCharsets.UTF_8), "DDL_DICT");
        Boolean useForExtraction = jdbcTemplate.queryForObject(
                "SELECT use_for_extraction FROM tp_document WHERE id = ?",
                Boolean.class, info.id());
        assertEquals(Boolean.TRUE, useForExtraction,
                "the whitelist key must fold the historical DDL_DICT behaviour at "
                        + "the same value -- turning it off here would be a tightening");
    }

    @Test
    void extractionAllowCategoriesKeyIsSeeded() {
        Integer seeded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_sys_config WHERE config_key = ?",
                Integer.class, "ai.doc.extraction-allow-categories");
        assertNotNull(seeded);
        assertEquals(1, seeded.intValue(), "the hot-update key must ship seeded");
        String value = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, "ai.doc.extraction-allow-categories");
        assertTrue(value.contains("TEST_SPEC") && value.contains("REQUIREMENT_DOC")
                        && value.contains("DDL_DICT") && !value.contains("REFERENCE"),
                "shipped default must equal the historical participation set, got: " + value);
    }
}

package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Document lineage chain tests : tp_document rows belonging to the
 * same logical document (same project + display name + file type) share one
 * lineage group; re-uploading into an existing group auto-pairs the new version
 * against the newest group member and records the change analysis without any
 * manual reanalyze call; the sha256 duplicate gate is group-scoped — identical
 * content inside the same group is a 409, identical content under a different
 * name (different group) is admitted.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class DocumentLineageChainTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37): only this suite's own project subtrees;
        // tp_doc_change_analysis and tp_audit_log ride the sweep table list.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
    }

    private void freshProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("血缘项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(projectId);
    }

    /** Body grows with the revision mark so every version has a distinct sha256. */
    private static byte[] versionedDoc(String revision) {
        String base = "# 部署规范\n\n## 发布窗口\n\n"
                + "发布窗口章节正文，含回滚说明。".repeat(8);
        return base.replace("回滚说明", "回滚说明（" + revision + "）")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] identicalBytes() {
        return "# 部署规范\n\n## 发布窗口\n\n完全相同的一份内容，用于组内查重。"
                .getBytes(StandardCharsets.UTF_8);
    }

    private int analysisRowCount() {
        // Project-scoped count: the dev database is shared, so a bare table count
        // leaks rows written by other fixtures/demo projects and makes the exact
        // expectation unstable (observed 4/10/12 across runs). The contract under
        // test is "this project's uploads leave exactly these rows", which the
        // project_id segment expresses faithfully.
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_doc_change_analysis WHERE project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }

    @Test
    void reupload_with_same_name_joins_one_lineage_group() {
        freshProject();
        DocumentInfo first = documentService.upload(1L, projectId, "部署规范.md",
                versionedDoc("v1"), null);
        DocumentInfo second = documentService.upload(1L, projectId, "部署规范.md",
                versionedDoc("v2"), null);
        assertNotNull(first.lineageGroupId(), "首个版本也必须落组（组=根文档 id）");
        assertEquals(first.lineageGroupId(), second.lineageGroupId(),
                "同名重传必须归入同一血缘组");
        DocumentInfo reread = documentService.get(second.id());
        assertEquals(first.lineageGroupId(), reread.lineageGroupId(),
                "列表/详情读方回显同一血缘组");
    }

    @Test
    void reupload_auto_pairs_against_newest_group_member() {
        freshProject();
        DocumentInfo v1 = documentService.upload(1L, projectId, "部署规范.md",
                versionedDoc("v1"), null);
        DocumentInfo v2 = documentService.upload(1L, projectId, "部署规范.md",
                versionedDoc("v2"), null);
        DocumentInfo v3 = documentService.upload(1L, projectId, "部署规范.md",
                versionedDoc("v3"), null);
        assertNotNull(v3);
        assertEquals(2, analysisRowCount(), "两次重传=两条自动配对的变更分析行");
        Long[] pair = jdbcTemplate.query(
                "SELECT old_document_id, new_document_id FROM tp_doc_change_analysis"
                        + " WHERE new_document_id = ?",
                (rs, rowNum) -> new Long[]{rs.getLong(1), rs.getLong(2)}, v3.id())
                .stream().findFirst().orElseThrow();
        assertEquals(v2.id(), pair[0], "自动配对锚=组内最新版（不是最早版）");
        assertEquals(v3.id(), pair[1]);
    }

    @Test
    void identical_content_within_group_is_rejected_with_human_message() {
        freshProject();
        byte[] same = identicalBytes();
        DocumentInfo first = documentService.upload(1L, projectId, "部署规范.md", same, null);
        assertNotNull(first);
        ServiceException dup = assertThrows(ServiceException.class,
                () -> documentService.upload(1L, projectId, "部署规范.md", same, null));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertEquals(0, analysisRowCount(), "重复拒收不得留下变更分析行");
    }

    @Test
    void identical_content_under_different_name_is_admitted() {
        freshProject();
        byte[] same = identicalBytes();
        DocumentInfo first = documentService.upload(1L, projectId, "部署规范.md", same, null);
        DocumentInfo twin = documentService.upload(1L, projectId, "运行手册.md", same, null);
        assertNotNull(twin, "异组（异名）同内容=新版本候选，不得按重复拒收");
        assertNotEquals(first.lineageGroupId(), twin.lineageGroupId(),
                "异名文档各自独立血缘组");
    }
}

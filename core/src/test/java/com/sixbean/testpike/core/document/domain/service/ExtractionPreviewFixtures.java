package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;


final class ExtractionPreviewFixtures {

    private ExtractionPreviewFixtures() {
    }

    record Docs(long projectId, long docId) {
    }

    
    static Docs preparingDoc(String prefix, ProjectService projectService,
                             DocumentService documentService, JdbcTemplate jdbcTemplate) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long projectId = projectService.create(1L,
                new ProjectCreateInput(prefix + tag, tag, "zh-CN", "P1", null));
        String md = "# 订单中心 PRD（" + tag + "）\n\n## 下单流程\n\n"
                + "用户提交订单时库存不足应提示下单失败。\n";
        DocumentInfo info = documentService.upload(1L, projectId,
                "PRD-" + tag + ".md", md.getBytes(StandardCharsets.UTF_8), null);
        return new Docs(projectId, info.id());
    }

    
    static void cleanDocsFixtures(JdbcTemplate jdbcTemplate, Long projectId, Long docId) {
        if (docId != null) {
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE source_id = ?", "doc:" + docId);
            jdbcTemplate.update("DELETE FROM tp_document WHERE id = ?", docId);
        }
        if (projectId != null) {
            // tp_user_project references the fixture project (membership row created
            // by projectService.create) — clear it before the project itself.
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
    }

    
    static void armExtracting(JdbcTemplate jdbcTemplate, long docId, String token) {
        jdbcTemplate.update("UPDATE tp_document SET status=3, extract_token=? WHERE id=?",
                token, docId);
    }

    
    static List<ExtractionItem> functionalOnlyItems() {
        return List.of(
                new ExtractionItem(0, "订单创建", "提交订单生成一单", "下单流程", 0.9, false, null),
                new ExtractionItem(0, "订单查询", "按单号查询订单状态", "查询流程", 0.8, false, null));
    }
}

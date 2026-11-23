package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.document.api.dto.DocChangeAnalysisInfo;
import com.sixbean.testpike.core.document.api.dto.DocChangeItem;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.component.DocChunkDiff;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class DocumentChangeAnalysisServiceTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentChangeAnalysisService analysisService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String code;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37): only this suite's own project subtrees;
        // tp_doc_change_analysis and tp_audit_log ride the sweep table list.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
    }

    private long createProject() {
        code = UUID.randomUUID().toString().substring(0, 8);
        long id = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    private static byte[] md(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    
    private static String doc(String tag) {
        return "# 订单中心 PRD\n\n## 下单流程\n\n下单正文" + tag + "。\n\n"
                + "## 支付流程\n\n支付正文。\n\n## 库存流程\n\n库存正文" + tag + "。\n";
    }

    
    private long seedRequirement(long projectId, long docId, String chapter, String title) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_requirement (project_id, source, source_id, title, status, chapter)"
                        + " VALUES (?, 2, ?, ?, 1, ?) RETURNING id", Long.class,
                projectId, "doc:" + docId, title, chapter);
    }

    
    private long linkCase(long projectId, long requirementId, String title) {
        long caseId = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", null, null, null, null, null, null, null)).id();
        jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id, created_by)"
                + " VALUES (?, ?, 1)", caseId, requirementId);
        return caseId;
    }

    @Test
    void test_reanalyze_chapter_diff_and_impact_chain_closed() {
        long projectId = createProject();
        DocumentInfo v1 = documentService.upload(1L, projectId, "订单PRD.md", md(doc("V1")), null);
        long reqOrder = seedRequirement(projectId, v1.id(), "订单中心 PRD > 下单流程", "下单需求");
        long caseOrder = linkCase(projectId, reqOrder, "下单用例");
        long reqStock = seedRequirement(projectId, v1.id(), "订单中心 PRD > 库存流程", "库存需求");
        long caseStock = linkCase(projectId, reqStock, "库存用例");
        DocumentInfo v2 = documentService.upload(1L, projectId, "订单PRD.md", md(doc("V2")), null);

        DocChangeAnalysisInfo info = analysisService.analyze(1L, v2.id());
        assertEquals(v1.id(), info.oldDocumentId(), "旧版锚定=项目内同名同类型更早成功版本");
        assertEquals(v2.id(), info.newDocumentId());
        assertTrue(info.chapterLocatable());
        assertEquals(0, info.status());
        assertNotNull(info.statusLabel());

        List<DocChangeItem> changes = info.changes();
        assertEquals(2, changes.size(), "变更集合=下单/库存两处 modify");
        assertEquals(DocChunkDiff.MODIFY, changes.get(0).changeType());
        DocChangeItem orderItem = changes.stream()
                .filter(c -> c.chapter() != null && c.chapter().endsWith("下单流程")).findFirst().orElseThrow();
        assertEquals(List.of(reqOrder), orderItem.requirementIds(), "引用链闭合：doc:{旧id}+chapter 等值");
        assertEquals(List.of(caseOrder), orderItem.caseIds());
        DocChangeItem stockItem = changes.stream()
                .filter(c -> c.chapter() != null && c.chapter().endsWith("库存流程")).findFirst().orElseThrow();
        assertEquals(List.of(reqStock), stockItem.requirementIds());
        assertEquals(List.of(caseStock), stockItem.caseIds());
    }

    @Test
    void test_reanalyze_chapter_not_matching_lists_change_with_empty_impact() {
        long projectId = createProject();
        DocumentInfo v1 = documentService.upload(1L, projectId, "手工漂移PRD.md", md(doc("V1")), null);
        long reqId = seedRequirement(projectId, v1.id(), "被人工改过的章节", "漂移需求");
        linkCase(projectId, reqId, "漂移用例");
        DocumentInfo v2 = documentService.upload(1L, projectId, "手工漂移PRD.md", md(doc("V3")), null);

        DocChangeAnalysisInfo info = analysisService.analyze(1L, v2.id());
        assertFalse(info.changes().isEmpty());
        assertTrue(info.changes().stream().allMatch(c -> c.requirementIds().isEmpty()),
                "坑B：chapter 不匹配=影响清单为空，禁假装闭合");
    }

    @Test
    void test_reanalyze_untitled_doc_degrades_to_whole_text() {
        long projectId = createProject();
        String body = "无标题正文段落。".repeat(30);
        DocumentInfo v1 = documentService.upload(1L, projectId, "无标题PRD.md",
                md(body + "第一段原文"), null);
        DocumentInfo v2 = documentService.upload(1L, projectId, "无标题PRD.md",
                md(body + "第二段改文"), null);

        DocChangeAnalysisInfo info = analysisService.analyze(1L, v2.id());
        assertFalse(info.chapterLocatable(), "坑A：无标题文档=全文单块对照，人话标注章节不可定位");
        assertEquals(1, info.changes().size());
        assertEquals(DocChunkDiff.MODIFY, info.changes().get(0).changeType());
        assertNull0(info.changes().get(0).chapter());
    }

    private static void assertNull0(String value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    void test_reanalyze_without_previous_version_rejected_400() {
        long projectId = createProject();
        DocumentInfo only = documentService.upload(1L, projectId, "首传PRD.md", md(doc("V1")), null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> analysisService.analyze(1L, only.id()));
        assertTrue(ex.getMessage().contains("没有可对照的历史版本"),
                "400 人话=无旧版锚定拒析，禁空分析行：" + ex.getMessage());
    }

    @Test
    void test_reanalyze_doc_not_found_404() {
        createProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> analysisService.analyze(1L, 999999999L));
        assertTrue(ex.getMessage().contains("文档不存在"), ex.getMessage());
    }

    @Test
    void test_get_analysis_not_found_404() {
        ServiceException ex = assertThrows(ServiceException.class, () -> analysisService.get(999999999L));
        assertTrue(ex.getMessage().contains("变更分析"), ex.getMessage());
    }

    @Test
    void test_handle_records_trace_and_flips_status_only() {
        long projectId = createProject();
        DocumentInfo v1 = documentService.upload(1L, projectId, "处置PRD.md", md(doc("V1")), null);
        DocumentInfo v2 = documentService.upload(1L, projectId, "处置PRD.md", md(doc("V5")), null);
        DocChangeAnalysisInfo info = analysisService.analyze(1L, v2.id());
        assertEquals(0, info.status());

        DocChangeAnalysisInfo handled = analysisService.handle(9L, info.id(), "已通知开发更新用例");
        assertEquals(1, handled.status());
        assertEquals("已处置", handled.statusLabel());
        assertEquals(9L, handled.handledBy(), "处置留痕=handled_by 落操作人");
        assertEquals("已通知开发更新用例", handled.handleNote());
        assertNotNull(handled.handledAt());
        assertEquals(info.changes(), handled.changes(), "处置仅改本行，清单不动");

        ServiceException dup = assertThrows(ServiceException.class,
                () -> analysisService.handle(9L, info.id(), "重复点击"));
        assertTrue(dup.getMessage().contains("已处置"), "重复处置=400 人话幂等拒：" + dup.getMessage());
    }

    @Test
    void test_handle_not_found_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> analysisService.handle(9L, 999999999L, "n"));
        assertTrue(ex.getMessage().contains("变更分析"), ex.getMessage());
    }

    @Test
    void test_analyze_notifies_project_owners_review_queue() {
        long projectId = createProject();
        DocumentInfo v1 = documentService.upload(1L, projectId, "通知PRD.md", md(doc("V1")), null);
        DocumentInfo v2 = documentService.upload(1L, projectId, "通知PRD.md", md(doc("V7")), null);
        DocChangeAnalysisInfo info = analysisService.analyze(1L, v2.id());

        Long notified = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'DOC_CHANGE_ANALYZED'"
                        + " AND project_id = ? AND dedup_key LIKE ?",
                Long.class, projectId, "DOC_CHANGE_ANALYZED:" + info.id() + ":%");
        assertTrue(notified != null && notified >= 1,
                "分析落库→项目负责人∪创建人收到复核提醒（P2 处置留痕通知链路）");
    }

    @Test
    void test_list_filters_by_status_and_pages() {
        long projectId = createProject();
        DocumentInfo v1 = documentService.upload(1L, projectId, "列表PRD.md", md(doc("V1")), null);
        DocumentInfo v2 = documentService.upload(1L, projectId, "列表PRD.md", md(doc("V6")), null);
        DocChangeAnalysisInfo a1 = analysisService.analyze(1L, v2.id());
        DocumentInfo v3 = documentService.upload(1L, projectId, "列表PRD.md", md(doc("V9")), null);
        DocChangeAnalysisInfo a2 = analysisService.analyze(1L, v3.id());
        analysisService.handle(9L, a1.id(), "第一单已处置");

        var pending = analysisService.list(projectId, 0, 1, 10);
        assertEquals(1, pending.total());
        assertEquals(List.of(a2.id()), pending.list().stream().map(DocChangeAnalysisInfo::id).toList());

        var all = analysisService.list(projectId, null, 1, 1);
        assertEquals(2, all.total(), "分页 total=全集计数非页内条数");
        assertEquals(1, all.list().size());
        assertEquals(a2.id(), all.list().get(0).id(), "复核队列序=id DESC 最新在前");
    }

    @Test
    void test_list_other_project_isolated() {
        long projectId = createProject();
        DocumentInfo v1 = documentService.upload(1L, projectId, "隔离PRD.md", md(doc("V1")), null);
        DocumentInfo v2 = documentService.upload(1L, projectId, "隔离PRD.md", md(doc("V2")), null);
        analysisService.analyze(1L, v2.id());
        long other = projectService.create(1L,
                new ProjectCreateInput("项目B" + code, code + "B", "zh-CN", "P1", null));
        assertEquals(0, analysisService.list(other, null, 1, 10).total(), "H1 项目隔离");
    }
}

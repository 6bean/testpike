package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.DocChangeAnalysisView;
import com.sixbean.testpike.admin.ai.api.dto.DocChangeItemView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.api.dto.HandleChangeRequest;
import com.sixbean.testpike.admin.ai.api.dto.ReanalyzeRequest;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocChangeAnalysisClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class DocChangeAnalysisControllerTest {

    private final CoreDocChangeAnalysisClient client = mock(CoreDocChangeAnalysisClient.class);
    private final CoreDocumentClient documentClient = mock(CoreDocumentClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final DocChangeAnalysisController controller =
            new DocChangeAnalysisController(client, documentClient, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    private static DocChangeAnalysisView rowFixture(long id, long projectId) {
        return new DocChangeAnalysisView(id, projectId, 11L, 12L,
                List.of(new DocChangeItemView("3.1 登录", "modify", List.of(21L), List.of(31L), true)),
                true, 0, "待复核", 7L, "2026-09-27 10:00:00", null, null, null);
    }

    @Test
    void 重传分析写路径_权限源走新文档归属_先取文档后验权() {
        LoggedUserFixture.loginAsTestLead(7L);
        DocumentInfoView doc = new DocumentInfoView(12L, 42L, "PRD.docx", "docx",
                "hash", 1024L, "k", 1, 3, null, null, true, true, true, 0, null, null, null);
        when(documentClient.get(12L)).thenReturn(doc);
        when(client.analyze(12L)).thenReturn(rowFixture(1L, 42L));
        Response<DocChangeAnalysisView> resp = controller.reanalyze(new ReanalyzeRequest(12L));
        assertEquals("0", resp.getCode());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanWriteRequirement(user.capture(), eq(42L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        verify(client).analyze(12L);
    }

    @Test
    void 文档不存在_验权前404人话透传_不打core分析链路() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(documentClient.get(99L)).thenThrow(
                new com.sixbean.testpike.common.exception.ServiceException("文档不存在", 404));
        try {
            controller.reanalyze(new ReanalyzeRequest(99L));
            org.junit.jupiter.api.Assertions.fail("应抛 404 人话");
        } catch (com.sixbean.testpike.common.exception.ServiceException expected) {
            assertTrue(expected.getMessage().contains("文档不存在"));
        }
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).analyze(99L);
    }

    @Test
    void 列表读路径_成员闸先过_筛选分页参数按位透传() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.list(3L, 0, 2, 20)).thenReturn(
                new PageView<>(List.of(rowFixture(1L, 3L)), 1L));
        Response<PageView<DocChangeAnalysisView>> resp = controller.list(3L, 0, 2, 20);
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().list().size());
        verify(guard).assertCanView(any(CurrentUser.class), eq(3L));
        verify(client).list(3L, 0, 2, 20);
    }

    @Test
    void 处置写路径_行归属反查后需求写闸_留痕note透传() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(5L)).thenReturn(rowFixture(5L, 43L));
        when(client.handle(5L, "已复核，用例无需变更")).thenReturn(
                new DocChangeAnalysisView(5L, 43L, 11L, 12L, List.of(), true, 1, "已处置",
                        7L, "2026-09-27 10:00:00", 7L, "2026-09-27 10:30:00", "已复核，用例无需变更"));
        Response<DocChangeAnalysisView> resp =
                controller.handle(5L, new HandleChangeRequest("已复核，用例无需变更"));
        assertEquals("0", resp.getCode());
        assertEquals("已处置", resp.getData().statusLabel());
        verify(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(43L));
        verify(client).handle(5L, "已复核，用例无需变更");
    }

    @Test
    void 处置可空留痕_note为null透传core() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(6L)).thenReturn(rowFixture(6L, 44L));
        when(client.handle(6L, null)).thenReturn(rowFixture(6L, 44L));
        controller.handle(6L, new HandleChangeRequest(null));
        verify(client).handle(6L, null);
    }

    @Test
    void 视图字段按名镜像core_状态人话与变更明细不静默丢() {
        DocChangeAnalysisView view = rowFixture(1L, 3L);
        assertEquals("待复核", view.statusLabel(), "人话标签源=core 单点，admin 禁二次映射");
        assertTrue(view.chapterLocatable());
        assertFalse(view.changes().isEmpty());
        DocChangeItemView item = view.changes().get(0);
        assertEquals("3.1 登录", item.chapter());
        assertEquals("modify", item.changeType());
        assertEquals(List.of(21L), item.requirementIds());
        assertEquals(List.of(31L), item.caseIds());
        assertEquals("2026-09-27 10:00:00", view.createdAt(), "时间格式化口径=ViewFormats 秒级");
    }
}

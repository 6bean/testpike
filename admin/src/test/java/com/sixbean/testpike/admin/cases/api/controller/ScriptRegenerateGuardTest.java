package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.audit.infrastructure.remote.client.CoreAuditClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.api.dto.FlowchartView;
import com.sixbean.testpike.admin.cases.api.dto.PreviewFlowchartRequest;
import com.sixbean.testpike.admin.cases.api.dto.ScriptView;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreScriptClient;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ScriptRegenerateGuardTest {

    private final CoreScriptClient coreScriptClient = mock(CoreScriptClient.class);
    private final CoreCaseClient coreCaseClient = mock(CoreCaseClient.class);
    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final CoreAuditClient coreAuditClient = mock(CoreAuditClient.class);

    private final ScriptController controller = new ScriptController(coreScriptClient,
            coreCaseClient, coreExecClient, guard, coreAuditClient);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

    private void givenCase(long projectId) {
        when(coreCaseClient.getCase(101L)).thenReturn(new CaseView(101L, "TC-1", projectId,
                "标题", null, null, 2, 0, "WEB", 3, null, 5, null, null,
                1, null, 7L, null, null, List.of(), List.of(), 0, null, null, null, 0, null, null, null, null));
    }

    private ScriptView script(int generatedBy, int version) {
        return new ScriptView(9L, 101L, "v2", "{}", 1, generatedBy, null, version, null, null);
    }


    @Test
    void regenerate_手编无force_needConfirm人话_不落任务不写审计() {
        login();
        givenCase(11L);
        when(coreScriptClient.getScript(101L)).thenReturn(script(4, 3));

        Response<Map<String, Object>> resp = controller.regenerate(101L, false);

        Map<String, Object> data = resp.getData();
        assertEquals(Boolean.TRUE, data.get("need_confirm"), "键名=契约字面 need_confirm: " + data);
        assertNull(data.get("taskId"), "确认态不得返回任务号: " + data);
        String message = (String) data.get("message");
        assertTrue(message != null && message.contains("手工编辑") && message.contains("v3"),
                "人话应含覆盖提示与当前版本: " + message);
        verify(coreScriptClient, never()).regenerateScript(anyLong(), anyLong(), anyLong());
        verify(coreAuditClient, never())
                .writeScriptRegenOverwrite(any(), anyLong(), anyLong(), anyLong(), anyLong());
    }


    @Test
    void regenerate_手编force_建任务并写覆盖审计() {
        login();
        givenCase(11L);
        when(coreScriptClient.getScript(101L)).thenReturn(script(4, 3));
        when(coreScriptClient.regenerateScript(101L, 11L, 7L)).thenReturn(555L);

        Response<Map<String, Object>> resp = controller.regenerate(101L, true);

        assertEquals(555L, resp.getData().get("taskId"));
        assertNull(resp.getData().get("need_confirm"));
        verify(coreScriptClient).regenerateScript(101L, 11L, 7L);
        verify(coreAuditClient).writeScriptRegenOverwrite(7L, 11L, 101L, 3L, 555L);
    }


    @Test
    void regenerate_AI来源_直建任务零审计() {
        login();
        givenCase(11L);
        when(coreScriptClient.getScript(101L)).thenReturn(script(1, 2));
        when(coreScriptClient.regenerateScript(101L, 11L, 7L)).thenReturn(666L);

        Response<Map<String, Object>> resp = controller.regenerate(101L, false);

        assertEquals(666L, resp.getData().get("taskId"));
        verify(coreAuditClient, never())
                .writeScriptRegenOverwrite(any(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void regenerate_无脚本_直建任务零审计() {
        login();
        givenCase(11L);
        when(coreScriptClient.getScript(101L)).thenReturn(null);
        when(coreScriptClient.regenerateScript(101L, 11L, 7L)).thenReturn(777L);

        Response<Map<String, Object>> resp = controller.regenerate(101L, false);

        assertEquals(777L, resp.getData().get("taskId"));
        verify(coreAuditClient, never())
                .writeScriptRegenOverwrite(any(), anyLong(), anyLong(), anyLong(), anyLong());
    }


    @Test
    void previewFlowchart_成员查看闸委托core() {
        login();
        givenCase(11L);
        when(coreScriptClient.previewFlowchart(101L, "{\"steps\":[]}"))
                .thenReturn(new FlowchartView("flowchart TD", 0));

        Response<FlowchartView> resp =
                controller.flowchartPreview(101L, new PreviewFlowchartRequest("{\"steps\":[]}"));

        assertEquals("flowchart TD", resp.getData().mermaid());
        verify(guard).assertCanView(any(), eq(11L));
        verify(guard, never()).assertCanWriteCase(any(), anyLong());
    }
}

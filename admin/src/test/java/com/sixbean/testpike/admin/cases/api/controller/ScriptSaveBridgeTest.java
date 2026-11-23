package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.api.dto.SaveScriptRequest;
import com.sixbean.testpike.admin.cases.api.dto.ScriptView;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreScriptClient;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ScriptSaveBridgeTest {

    private final CoreScriptClient coreScriptClient = mock(CoreScriptClient.class);
    private final CoreCaseClient coreCaseClient = mock(CoreCaseClient.class);
    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final com.sixbean.testpike.admin.audit.infrastructure.remote.client.CoreAuditClient coreAuditClient =
            mock(com.sixbean.testpike.admin.audit.infrastructure.remote.client.CoreAuditClient.class);

    private final ScriptController controller =
            new ScriptController(coreScriptClient, coreCaseClient, coreExecClient, guard, coreAuditClient);

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

    private ScriptView saved(int version) {
        return new ScriptView(9L, 101L, "v2", "{}", 1, 4, null, version, null, null);
    }

    
    @Test
    void blankPayloadRejected() {
        login();
        givenCase(11L);
        ServiceException e1 = assertThrows(ServiceException.class, () -> controller.saveScript(
                101L, 5, new SaveScriptRequest("  ", "{\"steps\":[]}")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e1.getCode());
        assertThrows(ServiceException.class, () -> controller.saveScript(
                101L, 5, new SaveScriptRequest("v2", " ")));
        assertThrows(ServiceException.class, () -> controller.saveScript(
                101L, null, new SaveScriptRequest("v2", "{\"steps\":[]}")));
    }

 
    @Test
    void generatedByForcedFourAndOperatorPassthrough() {
        login();
        givenCase(11L);
        when(coreScriptClient.saveScript(anyLong(), anyString(), anyString(), anyInt(),
                any(), any())).thenReturn(saved(6));

        controller.saveScript(101L, 5, new SaveScriptRequest("v2", "{\"steps\":[]}"));

        verify(coreScriptClient).saveScript(eq(101L), eq("v2"), eq("{\"steps\":[]}"),
                eq(4), eq(7L), eq(5));
    }

    
    @Test
    void strictGuardCombination() {
        login();
        givenCase(11L);
        when(coreScriptClient.saveScript(anyLong(), anyString(), anyString(), anyInt(),
                any(), any())).thenReturn(saved(6));
        controller.saveScript(101L, 5, new SaveScriptRequest("v2", "{\"steps\":[]}"));
        verify(guard).assertCanManage(any(), eq(11L));
        verify(guard).assertCanWriteCase(any(), eq(11L));
    }

    
    @Test
    void permissionKeyWired() throws Exception {
        Method m = ScriptController.class.getMethod("saveScript", long.class, Integer.class,
                SaveScriptRequest.class);
        assertEquals("perm:script:script-edit",
                m.getAnnotation(RequirePermission.class).value());
    }
}

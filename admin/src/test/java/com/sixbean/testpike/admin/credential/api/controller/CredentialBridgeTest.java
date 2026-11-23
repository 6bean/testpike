package com.sixbean.testpike.admin.credential.api.controller;

import com.sixbean.testpike.admin.credential.api.dto.CredentialSaveRequest;
import com.sixbean.testpike.admin.credential.api.dto.CredentialView;
import com.sixbean.testpike.admin.credential.infrastructure.remote.client.CoreCredentialClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;


class CredentialBridgeTest {

    private final CoreCredentialClient client = mock(CoreCredentialClient.class);
    private final CredentialController controller = new CredentialController(client,
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class));

    private CredentialView view(long id) {
        return new CredentialView(id, 7L, "测试账号口令", "qa_login_pwd", "******", 1,
                "只用于测试环境登录", LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void 建档_参数原样透传_返回core新凭据id() {
        CredentialSaveRequest request = new CredentialSaveRequest(7L, "测试账号口令",
                "qa_login_pwd", "plain-once-pass", "只用于测试环境登录");
        when(client.createCredential(eq(7L), eq(request))).thenReturn(88L);
        assertEquals(88L, controller.create(request).getData());
        verify(client).createCredential(7L, request);
    }

    @Test
    void 列表_enabledOnly过滤参数透传_core单点结果禁二套加工() {
        when(client.listCredentials(7L, null)).thenReturn(List.of(view(1L), view(2L)));
        assertEquals(2, controller.list(7L, null).getData().size());
        verify(client).listCredentials(eq(7L), eq(null));
        when(client.listCredentials(7L, Boolean.TRUE)).thenReturn(List.of(view(1L)));
        assertEquals(1, controller.list(7L, Boolean.TRUE).getData().size(),
                "仅启用位过滤在 core，admin 禁再过滤");
    }

    @Test
    void 详情_打码视图透传_core人话404原样上抛() {
        when(client.findCredential(1L)).thenReturn(view(1L));
        CredentialView got = controller.get(1L).getData();
        assertEquals("******", got.secretMask(), "读取口恒打码");
        assertTrue(got.remark().contains("测试环境"), "人话备注禁截断");
        when(client.findCredential(999L)).thenThrow(new ServiceException(
                "凭据不存在（可能已删除）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.get(999L));
        assertTrue(ex.getMessage().contains("凭据不存在"), "core 人话文案禁加工禁吞");
    }

    @Test
    void 编辑与启停与删除_按id回显归属透传_core撞键与禁用文案原样上抛() {
        CredentialSaveRequest request = new CredentialSaveRequest(7L, "改名",
                "qa_login_pwd", "******", null);
        when(client.findCredential(1L)).thenReturn(view(1L));
        controller.update(1L, request);
        verify(client).updateCredential(1L, request);
        controller.setEnabled(1L, false);
        verify(client).setCredentialEnabled(1L, false);
        controller.delete(1L);
        verify(client).deleteCredential(1L);
        when(client.findCredential(2L)).thenReturn(view(2L));
        doThrow(new ServiceException(
                "本项目已存在同引用键「qa_login_pwd」的凭据，请换一个键名或直接编辑既有凭据",
                BizErrorCode.RESOURCE_CONFLICT))
                .when(client).updateCredential(eq(2L), eq(request));
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> controller.update(2L, request));
        assertTrue(conflict.getMessage().contains("同引用键"), "409 人话点名禁加工");
    }

    @Test
    void 永不回显结构闸_视图组件面有打码位且零明文口令字段() {
        List<String> components = Arrays.stream(CredentialView.class.getRecordComponents())
                .map(c -> c.getName()).toList();
        assertTrue(components.contains("secretMask"), "打码位必须在场");
        List<String> leaky = components.stream()
                .filter(n -> n.toLowerCase().contains("secret") && !n.equals("secretMask"))
                .toList();
        assertTrue(leaky.isEmpty(), "视图禁出现明文/密文口令字段: " + leaky);
        // Bridge decryption ban = only the NAMED audited trail read doors are
        // allowed . The ledger itself must stay populated —
        // an emptied ledger reporting green is the silent no-op R43 forbids.
        List<String> clientMethods = Arrays.stream(CoreCredentialClient.class.getDeclaredMethods())
                .map(m -> m.getName()).toList();
        List<String> trailDoorLedger = List.of("revealTestTrailField", "testTrailFullText");
        assertTrue(clientMethods.containsAll(trailDoorLedger),
                "受门读口名册必须在场: " + clientMethods);
        List<String> rogue = clientMethods.stream()
                .filter(n -> n.toLowerCase().contains("reveal") && !trailDoorLedger.contains(n))
                .toList();
        assertTrue(rogue.isEmpty(), "admin 桥禁解密口（解密只在 core 派发前服务端注入）: " + rogue);
    }

    @Test
    void testNowDoorPassesTokenAndGuardOnTheRow() {
        when(client.findCredential(5L)).thenReturn(view(5L));
        when(client.testCredential(eq(5L), any())).thenReturn(
                new com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView(
                        "tok-9", "EXPIRED", "目标拒绝：会话或凭据疑似失效"));
        var out = controller.testNow(5L, new com.sixbean.testpike.admin.credential.api.dto.CredentialTestRequest(
                        "https://x.test/login", "POST", "{\"p\":\"{{CRED:k}}\"}"))
                .getData();
        assertEquals("tok-9", out.runToken());
        assertEquals("EXPIRED", out.verdict());
        verify(client).testCredential(eq(5L), any());
    }

    @Test
    void testEventsWindowPassesCursorThrough() {
        when(client.findCredential(5L)).thenReturn(view(5L));
        when(client.testEvents(5L, "tok-9", 3L)).thenReturn(List.of(
                new com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView(
                        4L, "step", "收到响应：HTTP 401", null, "2026-11-16T20:00:00")));
        var rows = controller.testEvents(5L, "tok-9", 3L).getData();
        assertEquals(1, rows.size());
        assertEquals(4L, rows.get(0).cursor());
        verify(client).testEvents(5L, "tok-9", 3L);
    }
}

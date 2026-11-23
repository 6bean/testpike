package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoEnvProfileView;
import com.sixbean.testpike.admin.appauto.api.dto.EnvProfileSaveRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoEnvProfileBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final AppautoEnvProfileController controller =
            new AppautoEnvProfileController(client, mock(
                    com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class));

    private AppautoEnvProfileView profile(long id) {
        return new AppautoEnvProfileView(id, 7L, "小程序-开发环境档案", "MP_DEVTOOLS",
                Map.of("appid", "wx1234567890abcdef", "projectPath", "/srv/mp-demo",
                        "cliPath", "C:/devtools/cli.bat", "autoPort", 9420),
                "正式版只能体验账号测——登录态失效先手工登录开发者工具",
                LocalDateTime.now(), LocalDateTime.now(), null);
    }

    @Test
    void 建档_参数原样透传_返回core新档id() {
        EnvProfileSaveRequest request = new EnvProfileSaveRequest(7L, "小程序-开发环境档案",
                "MP_DEVTOOLS", Map.of("appid", "wx1"), "备注", null);
        when(client.createEnvProfile(eq(7L), eq(request))).thenReturn(88L);
        assertEquals(88L, controller.create(request).getData());
        verify(client).createEnvProfile(7L, request);
    }

    @Test
    void 列表_档案型过滤参数透传_空即全量() {
        when(client.listEnvProfiles(7L, null)).thenReturn(List.of(profile(1L), profile(2L)));
        assertEquals(2, controller.list(7L, null).getData().size());
        verify(client).listEnvProfiles(eq(7L), isNull());
        when(client.listEnvProfiles(7L, "HARMONY_HDC")).thenReturn(List.of());
        assertTrue(controller.list(7L, "HARMONY_HDC").getData().isEmpty(),
                "core 单点过滤结果 admin 禁二套加工");
    }

    @Test
    void 详情_payload与remark完整透传_core人话404原样上抛() {
        when(client.findEnvProfile(1L)).thenReturn(profile(1L));
        AppautoEnvProfileView got = controller.get(1L).getData();
        assertEquals(9420, got.payload().get("autoPort"), "payload 数字保持数字类型不得漂成字符串");
        assertTrue(got.remark().contains("体验账号"), "人话提示位禁截断");
        when(client.findEnvProfile(999L)).thenThrow(new ServiceException(
                "环境档案不存在（可能已删除）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.get(999L));
        assertTrue(ex.getMessage().contains("环境档案不存在"), "core 人话文案禁加工禁吞");
    }

    @Test
    void 编辑与删除_按id回显归属透传_core撞名人话409原样上抛() {
        EnvProfileSaveRequest request = new EnvProfileSaveRequest(7L, "改名", "ANDROID_SDK",
                Map.of("sdkRoot", "/opt/android"), null, null);
        when(client.findEnvProfile(1L)).thenReturn(profile(1L));
        controller.update(1L, request);
        verify(client).updateEnvProfile(1L, request);
        controller.delete(1L);
        verify(client).deleteEnvProfile(1L);
        when(client.findEnvProfile(2L)).thenReturn(profile(2L));
        doThrow(new ServiceException(
                "本项目已存在同名环境档案「改名」，请换个名字或直接编辑既有档案",
                BizErrorCode.RESOURCE_CONFLICT))
                .when(client).updateEnvProfile(2L, request);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.update(2L, request));
        assertTrue(ex.getMessage().contains("请换个名字"), "撞名人话文案原样透出");
    }
}

package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoNlStepView;
import com.sixbean.testpike.admin.appauto.api.dto.NlStepGenerateRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoNlStepBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoNlStepController controller = new AppautoNlStepController(client, guard);

    private AppautoNlStepView draft() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", "app.tap");
        step.put("target", "登录按钮");
        step.put("timeoutMs", 3000);
        return new AppautoNlStepView(List.of(step));
    }

    @Test
    void 生成草稿_参数透传_查看闸按项目成员() {
        when(client.generateNlStep(7L, "MOBILEWRIGHT", "打开应用后点击登录")).thenReturn(draft());
        AppautoNlStepView got = controller.generate(
                new NlStepGenerateRequest(7L, "MOBILEWRIGHT", "打开应用后点击登录")).getData();
        assertEquals(1, got.steps().size(), "草稿步数原样透传");
        assertEquals(3000, got.steps().get(0).get("timeoutMs"), "数字保型禁字符串化");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).generateNlStep(7L, "MOBILEWRIGHT", "打开应用后点击登录");
    }

    @Test
    void core人话错误_关态拒与未知引擎_原样上抛不吞() {
        when(client.generateNlStep(eq(7L), eq("GHOST"), any()))
                .thenThrow(new ServiceException("引擎不存在：GHOST（请从引擎目录选择）",
                        BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.generate(new NlStepGenerateRequest(7L, "GHOST", "点击登录")));
        assertTrue(ex.getMessage().contains("引擎不存在"), "core 人话文案禁加工禁吞");
    }

    @Test
    void 空描述_必带项目号闸_由core单点人话拒_admin不二套校验() {
        when(client.generateNlStep(eq(7L), eq("MOBILEWRIGHT"), eq("")))
                .thenThrow(new ServiceException("操作描述不能为空", BizErrorCode.VALIDATION_ERROR));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.generate(new NlStepGenerateRequest(7L, "MOBILEWRIGHT", "")));
        assertTrue(ex.getMessage().contains("操作描述"), "校验单点在 core，admin 透传人话");
    }
}

package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoWxMockView;
import com.sixbean.testpike.admin.appauto.api.dto.WxMockGenerateRequest;
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


class AppautoWxMockBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoWxMockController controller = new AppautoWxMockController(client, guard);

    private Map<String, Object> contract() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("request", Map.of("openId", "string"));
        c.put("response", Map.of("name", "string", "idCard", "string"));
        return c;
    }

    @Test
    void 发起生成_查看闸与按键透传_应答三键原样直传() {
        WxMockGenerateRequest request = new WxMockGenerateRequest(
                7L, "/api/user/info", "POST", contract());
        when(client.generateWxMock(7L, "/api/user/info", "POST", request.contract()))
                .thenReturn(new AppautoWxMockView(
                        List.of(Map.of("scenario", "正常返回", "response",
                                Map.of("name", "张三", "idCard", "110101199003074591"))),
                        1, List.of("场景「假号」字段 response.idCard 身份证校验位不符")));
        AppautoWxMockView got = controller.generate(request).getData();
        assertEquals(1, got.mocks().size(), "复校通过场景行原样透传（admin 零加工）");
        assertEquals(1, got.pruned(), "剪除计数原样透传（前端人话提示消费）");
        assertEquals(1, got.pruneReasons().size(), "剪除点名清单原样透传（改错免翻文档）");
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void 关态403人话上抛不吞_桥零二套校验() {
        WxMockGenerateRequest request = new WxMockGenerateRequest(
                7L, "/api/user/info", null, contract());
        when(client.generateWxMock(7L, "/api/user/info", null, request.contract()))
                .thenThrow(new ServiceException("小程序 mock 智辅未开启——请到「系统设置 → APP 自动化」"
                        + "打开「小程序 mock 智辅」开关后重试", BizErrorCode.FORBIDDEN));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.generate(request));
        assertTrue(ex.getMessage().contains("系统设置"), "core 关态人话文案禁加工禁吞");
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void 全剪拒单引导占位符文案原样上抛() {
        WxMockGenerateRequest request = new WxMockGenerateRequest(
                7L, "/api/user/info", "GET", contract());
        when(client.generateWxMock(7L, "/api/user/info", "GET", request.contract()))
                .thenThrow(new ServiceException("模型产出的 3 个 mock 场景证件号校验位全部不符"
                        + "——请重试或让契约字段改用 ${gen.idCard(seed=数字)} 占位符写法",
                        BizErrorCode.VALIDATION_ERROR));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.generate(request));
        assertTrue(ex.getMessage().contains("${gen.idCard"), "全剪引导文案经桥不失真");
    }
}

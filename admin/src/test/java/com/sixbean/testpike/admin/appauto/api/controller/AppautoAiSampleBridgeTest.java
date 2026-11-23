package com.sixbean.testpike.admin.appauto.api.controller;

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


class AppautoAiSampleBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoAiSampleController controller = new AppautoAiSampleController(client, guard);

    private Map<String, Object> sample(long id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sampleId", id);
        row.put("projectId", 7L);
        row.put("caseId", 42L);
        row.put("deviceId", null);
        row.put("stepNo", 3);
        row.put("anchor", "登录|按钮|text=登录");
        row.put("hitSource", "vision");
        row.put("reviewResult", "confirmed");
        row.put("x", 180);
        row.put("y", 640);
        row.put("sourceTaskId", 900L);
        return row;
    }

    @Test
    void 样本列表_参数透传_查看闸按项目成员() {
        when(client.listAiSamples(7L, 42L, 50)).thenReturn(List.of(sample(1L)));
        List<Map<String, Object>> got = controller.listSamples(7L, 42L, 50).getData();
        assertEquals(1, got.size());
        assertEquals("confirmed", got.get(0).get("reviewResult"), "应答 Map 直传禁加工");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).listAiSamples(7L, 42L, 50);
    }

    @Test
    void 命中率统计_实算口径透传_core单点禁二套() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", 10L);
        stats.put("confirmed", 6L);
        stats.put("rejected", 4L);
        stats.put("confirmRate", 0.6);
        when(client.aiSampleHitRate(7L, 42L)).thenReturn(stats);
        Map<String, Object> got = controller.hitRate(7L, 42L).getData();
        assertEquals(0.6, got.get("confirmRate"), "命中率=core 实算值原样，禁 admin 重算");
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void core人话错误_原样上抛不吞() {
        when(client.aiSampleHitRate(7L, 99L)).thenThrow(new ServiceException(
                "用例不存在：99（请先在用例列表确认编号）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.hitRate(7L, 99L));
        assertTrue(ex.getMessage().contains("用例不存在"), "core 人话文案禁加工禁吞");
    }
}

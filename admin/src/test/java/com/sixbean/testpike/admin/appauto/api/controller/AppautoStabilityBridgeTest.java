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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoStabilityBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoStabilityController controller = new AppautoStabilityController(client, guard);

    private Map<String, Object> row(Long deviceId, int flips, boolean flaky, double score) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", 42L);
        row.put("deviceId", deviceId);
        row.put("samples", 6);
        row.put("flips", flips);
        row.put("flaky", flaky);
        row.put("reason", flaky ? "窗口内通过↔失败翻转 3 次，疑似 flaky" : null);
        row.put("stabilityScore", score);
        return row;
    }

    @Test
    void 稳定性分行_参数透传_查看闸按项目成员() {
        when(client.listStabilityRows(7L, 42L)).thenReturn(List.of(row(3L, 3, true, 0.5)));
        List<Map<String, Object>> got = controller.stabilityRows(7L, 42L).getData();
        assertEquals(1, got.size());
        assertEquals(0.5, got.get(0).get("stabilityScore"), "稳定性分=core 实算值原样，禁 admin 重算");
        assertEquals(3, got.get(0).get("flips"), "翻转数单点=FlakyRuleCalculator 同源，禁二套");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).listStabilityRows(7L, 42L);
    }

    @Test
    void 设备未知组_null_诚实透传_禁admin补猜() {
        when(client.listStabilityRows(7L, 42L)).thenReturn(List.of(row(null, 0, false, 1.0)));
        List<Map<String, Object>> got = controller.stabilityRows(7L, 42L).getData();
        assertNull(got.get(0).get("deviceId"), "旧 leaseRef 形态=设备未知组 null 原样，禁按 serial 猜");
    }

    @Test
    void 空历史_诚实空列表_非null非异常() {
        when(client.listStabilityRows(7L, 99L)).thenReturn(List.of());
        List<Map<String, Object>> got = controller.stabilityRows(7L, 99L).getData();
        assertTrue(got.isEmpty(), "无终态样本=空数组诚实（看板/U6 消费面口径同 ai-samples）");
    }

    @Test
    void core人话错误_原样上抛不吞() {
        when(client.listStabilityRows(7L, 99L)).thenThrow(new ServiceException(
                "用例不存在：99（请先在用例列表确认编号）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.stabilityRows(7L, 99L));
        assertTrue(ex.getMessage().contains("用例不存在"), "core 人话文案禁加工禁吞");
    }
}

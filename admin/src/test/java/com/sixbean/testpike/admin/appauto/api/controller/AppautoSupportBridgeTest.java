package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoSupportView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeAdviceRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeAdviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeFlakyLinkView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeWindowView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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


class AppautoSupportBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoSupportController controller = new AppautoSupportController(client, guard);

    @Test
    void 支持度抽屉_参数透传_查看闸按项目成员_字段零加工() {
        AppautoSupportView row = new AppautoSupportView("MOBILEWRIGHT", "Mobilewright", true,
                "GREEN", "在支持矩阵内", "YELLOW", 40, 0.85,
                List.of(Map.of("platform", "ANDROID", "samples", 40)),
                "版本合规但通过率跌破线，先看归因", "口径=近 7 天终态行 PassRateSql 单谱");
        when(client.engineSupport(7L)).thenReturn(List.of(row));
        List<AppautoSupportView> got = controller.engineSupport(7L).getData();
        assertEquals(0.85, got.get(0).healthPassRate(), 1e-9, "通过率=core 实算值原样，禁 admin 重算");
        assertEquals("版本合规但通过率跌破线，先看归因", got.get(0).conclusion(), "一句话结论直传禁加工");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).engineSupport(7L);
    }

    @Test
    void 升级顾问_入参照样转发_风险清单随行直传() {
        AppautoUpgradeAdviceRequest request = new AppautoUpgradeAdviceRequest(7L, "0.0.70");
        when(client.upgradeAdvice(7L, "0.0.70")).thenReturn(new AppautoUpgradeAdviceView(
                List.of("34 条用例依赖 AI 视觉定位"), "整体中风险",
                List.of("定位语义变更"), Map.of("scriptCases", 34), "画像实算摘要"));
        AppautoUpgradeAdviceView got = controller.upgradeAdvice(request).getData();
        assertEquals(1, got.risks().size());
        assertEquals(34, got.profile().get("scriptCases"), "画像随行回读=core 同源值原样");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).upgradeAdvice(7L, "0.0.70");
    }

    @Test
    void 排窗与归因_机群全局读面_逐行原样透出() {
        LocalDateTime now = LocalDateTime.now();
        when(client.upgradeWindow()).thenReturn(List.of(
                new AppautoUpgradeWindowView(now, 0, true, "4 节点：金丝雀 1 台再分批")));
        when(client.upgradeFlakyLinks()).thenReturn(List.of(
                new AppautoUpgradeFlakyLinkView(9L, "0.0.70", now.minusHours(5),
                        50, 0.92, 44, 0.61, 31.0, "疑似升级引入")));
        List<AppautoUpgradeWindowView> slots = controller.upgradeWindow().getData();
        assertTrue(slots.get(0).recommended(), "推荐位=core 现算，admin 零二次判定");
        List<AppautoUpgradeFlakyLinkView> links = controller.upgradeFlakyLinks().getData();
        assertEquals(31.0, links.get(0).dropPoints(), 1e-9, "骤降点数直传禁重算");
    }

    @Test
    void core人话错误_原样上抛不吞() {
        when(client.engineSupport(99L)).thenThrow(new ServiceException(
                "项目不存在：99（请先在项目列表确认编号）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.engineSupport(99L));
        assertTrue(ex.getMessage().contains("项目不存在"), "core 人话文案禁加工禁吞");
        when(client.upgradeAdvice(7L, null)).thenThrow(new ServiceException(
                "升级顾问未开启（系统设置 → APP 自动化 → ai.appauto.upgrade-advisor）",
                BizErrorCode.FORBIDDEN));
        ServiceException off = assertThrows(ServiceException.class,
                () -> controller.upgradeAdvice(new AppautoUpgradeAdviceRequest(7L, null)));
        assertTrue(off.getMessage().contains("未开启"), "关态 403 人话原样上抛");
    }
}

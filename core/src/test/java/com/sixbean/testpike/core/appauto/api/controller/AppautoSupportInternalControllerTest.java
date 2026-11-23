package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoCaseFeatureProfile;
import com.sixbean.testpike.core.appauto.api.dto.AppautoHealthGroupView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoSupportView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeFlakyLinkView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeWindowView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoSupportService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeAdvisorService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeFlakyLinkService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeWindowService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoSupportInternalControllerTest {

    private final AppautoSupportService supportService = mock(AppautoSupportService.class);
    private final AppautoUpgradeAdvisorService advisorService = mock(AppautoUpgradeAdvisorService.class);
    private final AppautoUpgradeWindowService windowService = mock(AppautoUpgradeWindowService.class);
    private final AppautoUpgradeFlakyLinkService flakyLinkService = mock(AppautoUpgradeFlakyLinkService.class);
    private final AppautoSupportInternalController controller = new AppautoSupportInternalController(
            supportService, advisorService, windowService, flakyLinkService);

    private AppautoSupportView supportRow() {
        Map<String, Integer> errorDist = new LinkedHashMap<>();
        errorDist.put("元素未找到", 3);
        Map<String, Integer> aiDist = new LinkedHashMap<>();
        aiDist.put("未归类", 1);
        AppautoHealthGroupView group = new AppautoHealthGroupView("ANDROID", 40, 34, 0.85,
                errorDist, aiDist);
        return new AppautoSupportView("MOBILEWRIGHT", "Mobilewright", true,
                "GREEN", "在支持矩阵内（要求 >=0.0.60）",
                "YELLOW", 40, 0.85, List.of(group),
                "版本合规但近 7 天通过率 85.0% 跌破线，先看归因分布再决定灰度",
                "数据源=APP 执行历史终态行（3/4/5 进分母），窗口=K8 键近 7 天，通过率谱=PassRateSql 单源");
    }

    @Test
    void 支持度抽屉_按项目转发_十一字段原样透出() {
        when(supportService.support(7L)).thenReturn(List.of(supportRow(),
                new AppautoSupportView(null, "未知引擎", false, "UNKNOWN", null,
                        "UNKNOWN", 0, 0, List.of(), null, null)));
        List<AppautoSupportView> got = controller.engineSupport(7L).getData();
        assertEquals(2, got.size(), "下架引擎行与未知引擎组诚实透出，admin/core 均禁丢行补猜");
        assertEquals("YELLOW", got.get(0).healthState());
        assertEquals(0.85, got.get(0).healthPassRate(), 1e-9, "通过率=core 实算值原样，禁二套");
        assertEquals("元素未找到", List.copyOf(got.get(0).healthGroups().get(0).errorTypeDist().keySet()).get(0));
        assertNull(got.get(1).engineCode(), "信封缺引擎行=未知引擎组 engineCode=null 诚实呈现");
        verify(supportService).support(7L);
    }

    @Test
    void 升级顾问_入参照样转发_风险清单随行直传() {
        AppautoUpgradeAdviceRequest request = new AppautoUpgradeAdviceRequest(7L, "0.0.70");
        AppautoCaseFeatureProfile profile = new AppautoCaseFeatureProfile(34,
                Map.of("app.tap", 120, "app.input", 40), Map.of("MOBILEWRIGHT", 30), 8, 5);
        when(advisorService.advise(request)).thenReturn(new AppautoUpgradeAdviceView(
                List.of("34 条用例依赖 AI 视觉定位，大版本变更定位语义，建议先跑这 34 条金丝雀"),
                "整体中风险", List.of("定位语义变更：resource-id 匹配策略调整"),
                profile, "画像实算：34 用例有现行脚本，AI 样本 8 用例，webview 步 5"));
        AppautoUpgradeAdviceView got = controller.upgradeAdvice(request).getData();
        assertEquals(1, got.risks().size());
        assertEquals(120, got.profile().verbCounts().get("app.tap"), "画像=core 实算下传同源随行回读，禁模型自行取数");
        verify(advisorService).advise(request);
    }

    @Test
    void 排窗曲线_逐槽透出_推荐位单点在core() {
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        when(windowService.recommend()).thenReturn(List.of(
                new AppautoUpgradeWindowView(now, 3, false, null),
                new AppautoUpgradeWindowView(now.plusHours(1), 0, true,
                        "集群 4 节点：金丝雀 1 台 → 分 2 批（每批 ≤2 台），批间冷却按 K8 键")));
        List<AppautoUpgradeWindowView> got = controller.upgradeWindow().getData();
        assertEquals(2, got.size());
        assertTrue(got.get(1).recommended(), "推荐位=core 忙闲曲线现算，本口零二次判定");
        assertEquals(3, got.get(0).busyBatches());
        verify(windowService).recommend();
    }

    @Test
    void flaky归因关联行_九字段原样() {
        LocalDateTime at = LocalDateTime.now().minusHours(5);
        when(flakyLinkService.correlate()).thenReturn(List.of(
                new AppautoUpgradeFlakyLinkView(9L, "0.0.70", at, 50, 0.92, 44, 0.61, 31.0,
                        "升级后 24h 通过率骤降 31 个百分点，疑似升级引入，建议回滚或查归因")));
        List<AppautoUpgradeFlakyLinkView> got = controller.upgradeFlakyLinks().getData();
        assertEquals(1, got.size());
        assertEquals(31.0, got.get(0).dropPoints(), 1e-9, "骤降百分点=前后窗实算差，禁本口重算");
        verify(flakyLinkService).correlate();
    }

    @Test
    void 关态与红线人话异常_原样上抛不吞() {
        AppautoUpgradeAdviceRequest request = new AppautoUpgradeAdviceRequest(7L, "0.0.70");
        when(advisorService.advise(request)).thenThrow(new ServiceException(
                DenialGuidanceFrame.switchClosed("升级顾问", "ai.appauto.upgrade-advisor"),
                BizErrorCode.FORBIDDEN));
        ServiceException err = assertThrows(ServiceException.class, () -> controller.upgradeAdvice(request));
        assertTrue(err.getMessage().contains("升级顾问当前未开启：请在「系统管理 → 系统配置」搜索"), "关态 403 文案禁加工禁吞");
        when(windowService.recommend()).thenThrow(new ServiceException(
                "该窗有在跑升级计划，禁推荐排窗", BizErrorCode.VALIDATION_ERROR));
        assertThrows(ServiceException.class, () -> controller.upgradeWindow());
    }
}

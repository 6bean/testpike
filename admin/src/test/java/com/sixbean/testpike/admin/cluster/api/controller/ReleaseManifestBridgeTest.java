package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreReleaseManifestClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ReleaseManifestBridgeTest {

    private final CoreReleaseManifestClient client = mock(CoreReleaseManifestClient.class);
    private final ReleaseManifestController controller = new ReleaseManifestController(client);

    @Test
    void 登记桥_七字段原样透传() {
        LocalDateTime now = LocalDateTime.now();
        when(client.register(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ReleaseManifestView(1L, "1.4.0", "[]", "[]", "1.3.0", "1.5.0",
                        "先到 1.3 再升", "manual", now, "[]", null, null));

        controller.register(new ReleaseRegisterRequest("1.4.0",
                "[{\"component\":\"executor-agent\"}]", "[\"定位语义变更需重录\"]",
                "1.3.0", "1.5.0", "先到 1.3 再升", "manual",
                "[{\"component_family\":\"executor-agent\"}]", "release notes"));

        ArgumentCaptor<ReleaseRegisterRequest> captor =
                ArgumentCaptor.forClass(ReleaseRegisterRequest.class);
        verify(client).register(captor.capture());
        ReleaseRegisterRequest sent = captor.getValue();
        assertEquals("1.4.0", sent.version());
        assertEquals("[{\"component\":\"executor-agent\"}]", sent.componentMatrixJson(),
                "部件矩阵 json 串零加工透传");
        assertEquals("1.3.0", sent.executorVersionMin());
        assertEquals("1.5.0", sent.executorVersionMax());
        assertEquals("先到 1.3 再升", sent.suggestedPath());
        assertEquals("manual", sent.source());
        assertEquals("[{\"component_family\":\"executor-agent\"}]", sent.artifactsJson(),
                "工件清单零加工透传");
        assertEquals("release notes", sent.releaseNotesMd(), "发布说明零加工透传");
    }

    @Test
    void 清单读回_九字段完整投影_latest空表null原样透出() {
        LocalDateTime now = LocalDateTime.now();
        when(client.latest()).thenReturn(null);
        assertNull(controller.latest().getData(), "未导入=诚实 null（桥面禁填占位谎报）");

        when(client.listAll()).thenReturn(List.of(
                new ReleaseManifestView(1L, "1.4.0",
                        "[{\"component\":\"executor-agent\",\"pinned\":\"1.4.2\"}]",
                        "[\"协议变更人话句\"]", "1.3.0", "1.5.0", "跳版限制句", "import", now,
                        "[{\"component_family\":\"executor-agent\",\"url\":\"https://github.com/a\"}]",
                        "notes body", now)));
        List<ReleaseManifestView> all = controller.list().getData();
        assertEquals(1, all.size());
        ReleaseManifestView m = all.get(0);
        assertEquals("1.4.0", m.version());
        assertTrue(m.componentMatrix().contains("executor-agent"), "部件矩阵不截断");
        assertTrue(m.breakingChanges().contains("协议变更"), "破坏性变更不截断");
        assertEquals("跳版限制句", m.suggestedPath());
        assertEquals("import", m.source());
        assertEquals(now, m.registeredAt());
    }

    @Test
    void 体检卡桥_判定列原样透出_core人话异常上抛() {
        when(client.compatDiff()).thenReturn(List.of(
                new com.sixbean.testpike.admin.cluster.api.dto.ExecutorCompatView(
                        7L, "ex-old", "1.2.0", false, "与本平台协议不兼容（需 1.3~1.5）")));
        var diff = controller.compatDiff().getData();
        assertEquals(1, diff.size());
        assertEquals(Boolean.FALSE, diff.get(0).compatible(), "红态判定列原样透出（admin 禁再判定）");
        assertTrue(diff.get(0).reason().contains("不兼容"), "人话理由不加工");

        when(client.register(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ServiceException("发布清单登记必须提供版本号（version）"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.register(new ReleaseRegisterRequest(null, "[]", "[]",
                        null, null, null, null, null, null)));
        assertTrue(e.getMessage().contains("版本号"), "core 人话校验句原样上抛：" + e.getMessage());
    }

    @Test
    void compatDiffWithBridge_forwardsBoundsVerdictUntouched() {
        // Candidate-drawer preview: the bridge forwards min/max as-is and re-exposes
        // core's verdict rows without re-scoring (red counting happens on the core side).
        when(client.compatDiffAgainst("1.3.0", "1.5.0")).thenReturn(List.of(
                new com.sixbean.testpike.admin.cluster.api.dto.ExecutorCompatView(
                        9L, "ex-new", "1.6.0", false, "与本平台协议不兼容（需 1.3~1.5）")));
        var preview = controller.compatDiffWith("1.3.0", "1.5.0").getData();
        assertEquals(1, preview.size());
        assertEquals(Boolean.FALSE, preview.get(0).compatible(), "hypothetical verdict passed through");
        verify(client).compatDiffAgainst("1.3.0", "1.5.0");

        when(client.compatDiffAgainst(null, null)).thenThrow(
                new ServiceException("假想兼容区间不合法：下界 2.0.0 大于上界 1.0.0"));
        assertThrows(ServiceException.class, () -> controller.compatDiffWith(null, null));
    }

    @Test
    void 平台版本桥_四字段原样透出_缺省诚实null_异常上抛() {
        when(client.platformVersion()).thenReturn(
                new com.sixbean.testpike.admin.cluster.api.dto.PlatformVersionView(
                        "1.4.0", "2026-10-12T15:00:00Z", "1.4.0", "UP_TO_DATE"));
        var v = controller.platformVersion().getData();
        org.junit.jupiter.api.Assertions.assertEquals("1.4.0", v.runningVersion());
        org.junit.jupiter.api.Assertions.assertEquals("2026-10-12T15:00:00Z", v.buildTime());
        org.junit.jupiter.api.Assertions.assertEquals("1.4.0", v.latestVersion());
        org.junit.jupiter.api.Assertions.assertEquals("UP_TO_DATE", v.verdict(),
                "五判词由 core VersionCompare 单源现算，桥面禁再判定");

        when(client.platformVersion()).thenReturn(
                new com.sixbean.testpike.admin.cluster.api.dto.PlatformVersionView(
                        null, null, null, "UNKNOWN_BUILD"));
        assertNull(controller.platformVersion().getData().runningVersion(),
                "dev 运行无 build-info=诚实 null，桥面禁填占位谎报");

        when(client.platformVersion()).thenThrow(new ServiceException("core 侧人话异常"));
        ServiceException e = assertThrows(ServiceException.class, controller::platformVersion);
        assertTrue(e.getMessage().contains("人话异常"), "core 异常原样上抛不吞");
    }
}

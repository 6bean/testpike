package com.sixbean.testpike.admin.batch.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.batch.api.dto.BatchInfoView;
import com.sixbean.testpike.admin.batch.api.dto.BatchReportView;
import com.sixbean.testpike.admin.batch.api.dto.MonitorEpisodeView;
import com.sixbean.testpike.admin.batch.api.dto.MobilePerfMetricView;
import com.sixbean.testpike.admin.batch.api.dto.MobilePerfRollupView;
import com.sixbean.testpike.admin.batch.infrastructure.remote.client.CoreBatchClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class BatchReportBridgeTest {

    private final CoreBatchClient coreBatchClient = mock(CoreBatchClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final BatchController controller = new BatchController(coreBatchClient, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private CurrentUser login() {
        CurrentUser user = new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null);
        UserContext.set(user);
        return user;
    }

    private BatchInfoView batch7() {
        return new BatchInfoView(7L, 3L, "B7", "回归批", null, "默认环境", 4, "部分失败", 1, null,
                5, null, null, null, null, null, 7, null, 0, null, null, 0, null);
    }

    
    @Test
    void detailBridgePassesEnvOngoingEpisode() {
        login();
        when(coreBatchClient.get(7L)).thenReturn(new BatchInfoView(7L, 3L, "B7", "回归批",
                2L, "默认环境", 4, "执行中", 1, null,
                5, null, null, null, null, null, 7, null, 0, null, null, 0,
                new MonitorEpisodeView(11L, 5L, "登录链路", "LOGIN", 2L,
                        java.time.LocalDateTime.now().minusMinutes(12),
                        null, true, 3, "SSO 回调 502")));

        BatchInfoView out = controller.get(7L).getData();

        assertNotNull(out.envOngoingEpisode(), "进行中环境事件桥原样透传禁吞");
        assertEquals("登录链路", out.envOngoingEpisode().targetName());
        assertEquals(Boolean.TRUE, out.envOngoingEpisode().ongoing());
    }

    
    @Test
    void memberReadsSnapshotReportViaCore() {
        CurrentUser user = login();
        when(coreBatchClient.get(7L)).thenReturn(batch7());
        BatchReportView view = new BatchReportView(7L, 7, "部分失败",
                5, 3, 1, 0, 1, new BigDecimal("0.6000"),
                Map.of("1", 1), Map.of("ai_locate", 1),
                123456L, "v1.2.3",
                java.util.List.of(new MonitorEpisodeView(11L, 5L, "登录链路", "LOGIN", 2L,
                        java.time.LocalDateTime.now().minusHours(2),
                        java.time.LocalDateTime.now().minusHours(1), false, 9, "SSO 回调 502")),
                2, 1,
                new MobilePerfRollupView(Map.of("cpuPercent",
                        new MobilePerfMetricView(new BigDecimal("15.00"), 2,
                                new BigDecimal("10.00"), new BigDecimal("5.00"))),
                        4L, 10L));
        when(coreBatchClient.report(7L)).thenReturn(view);

        Response<BatchReportView> resp = controller.report(7L);

        verify(guard).assertCanView(user, 3L);
        verify(coreBatchClient).report(7L);
        assertSame(view, resp.getData());
        BatchReportView out = resp.getData();
        assertEquals(5, out.total());
        assertEquals(3, out.passCount());
        assertEquals(1, out.failCount());
        assertEquals(0, out.skipCount());
        assertEquals(1, out.timeoutCount());
        assertEquals(new BigDecimal("0.6000"), out.passRate(), "通过率=快照定标原值透传，桥禁重算");
        assertEquals(Map.of("1", 1), out.failDist());
        assertEquals(Map.of("ai_locate", 1), out.attr());
        assertEquals(123456L, out.durationMs());
        assertEquals("v1.2.3", out.envVersionSnapshot());
        assertEquals(1, out.monitorEvidence().size(), "归因证据字段桥原样透传禁吞");
        assertEquals("登录链路", out.monitorEvidence().get(0).targetName());
        assertEquals(2, out.mockTaskCount(), "mock 计数=core 快照值桥原样透传禁重算");
        assertEquals(1, out.llmTaskCount(), "AI 兜底计数=core 快照值桥原样透传禁重算");
        assertEquals(15.0, out.mobilePerf().metrics().get("cpuPercent").median().doubleValue(), 0.001,
                "mobile rollup = core snapshot value forwarded untouched");
        assertEquals(10.0, out.mobilePerf().metrics().get("cpuPercent").prevMedian().doubleValue(), 0.001);
        assertEquals(5.0, out.mobilePerf().metrics().get("cpuPercent").delta().doubleValue(), 0.001);
        assertEquals(4L, out.mobilePerf().prevBatchId().longValue(), "comparison anchor forwarded");
        assertEquals(10L, out.mobilePerf().sampleCount().longValue());
    }

    /**
     * Wire contract of the mobile rollup mirror: the JSON core emits from
     * {@code tp_batch_report.mobile_perf_json} deserializes into the admin record with
     * every field intact, so the bridge forwards numbers it never parses. A metric the
     * previous cycle never reported arrives with null comparison fields — never a
     * zero-filled median or delta the page could mistake for real evidence.
     */
    @Test
    void mobilePerfRollupSurvivesTheJsonMirror() throws Exception {
        String corePayload = "{\"batchId\":7,\"batchStatus\":6,\"total\":2,\"passCount\":2,"
                + "\"failCount\":0,\"skipCount\":0,\"timeoutCount\":0,\"passRate\":1.0000,"
                + "\"failDist\":{},\"attr\":{},\"durationMs\":null,\"envVersionSnapshot\":null,"
                + "\"monitorEvidence\":[],\"mockTaskCount\":0,\"llmTaskCount\":0,"
                + "\"mobilePerf\":{\"metrics\":{"
                + "\"cpuPercent\":{\"median\":15.0,\"taskCount\":2,\"prevMedian\":10.0,\"delta\":5.0},"
                + "\"launchTimeMs\":{\"median\":800.0,\"taskCount\":2}},"
                + "\"prevBatchId\":4,\"sampleCount\":10}}";

        BatchReportView out = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(corePayload, BatchReportView.class);

        assertNotNull(out.mobilePerf(), "rollup survives the mirror");
        assertEquals(2, out.mobilePerf().metrics().size());
        MobilePerfMetricView cpu = out.mobilePerf().metrics().get("cpuPercent");
        assertEquals(15.0, cpu.median().doubleValue(), 0.001);
        assertEquals(2, cpu.taskCount().intValue());
        assertEquals(10.0, cpu.prevMedian().doubleValue(), 0.001);
        assertEquals(5.0, cpu.delta().doubleValue(), 0.001);
        MobilePerfMetricView launch = out.mobilePerf().metrics().get("launchTimeMs");
        assertNull(launch.prevMedian(), "metric absent in the previous cycle stays absent");
        assertNull(launch.delta());
        assertEquals(4L, out.mobilePerf().prevBatchId().longValue());
        assertEquals(10L, out.mobilePerf().sampleCount().longValue());
    }

    
    @Test
    void nullableFieldsPassThroughUntouched() {
        login();
        when(coreBatchClient.get(7L)).thenReturn(batch7());
        when(coreBatchClient.report(7L)).thenReturn(new BatchReportView(7L, 6, "已完成",
                2, 2, 0, 0, 0, null, Map.of(), Map.of(), null, null, List.of(), 0, 0, null));

        BatchReportView out = controller.report(7L).getData();

        assertNull(out.passRate());
        assertNull(out.durationMs());
        assertNull(out.envVersionSnapshot());
        assertEquals(Map.of(), out.failDist());
        assertEquals(0, out.mockTaskCount(), "plain batch = 0 mock tasks passes through as 0");
    }

    
    @Test
    void reportEndpointCarriesBatchViewPermission() throws Exception {
        Method m = BatchController.class.getMethod("report", long.class);
        assertNotNull(m);
        GetMapping get = m.getAnnotation(GetMapping.class);
        assertEquals("/{id}/report", get.value()[0]);
        RequestMapping rm = BatchController.class.getAnnotation(RequestMapping.class);
        assertEquals("/api/v1/batches", rm.value()[0]);
        com.sixbean.testpike.admin.rbac.app.component.RequirePermission perm =
                m.getAnnotation(com.sixbean.testpike.admin.rbac.app.component.RequirePermission.class);
        assertNotNull(perm, "report 端点必须挂权限键");
        assertEquals("perm:batch:batch-view", perm.value());
    }

    
    @Test
    void guardRejectionShortCircuitsCoreCall() {
        login();
        when(coreBatchClient.get(7L)).thenReturn(batch7());
        org.mockito.Mockito.doThrow(new RuntimeException("no access"))
                .when(guard).assertCanView(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3L));

        try {
            controller.report(7L);
            org.junit.jupiter.api.Assertions.fail("闸拒必须上抛，禁静默放行");
        } catch (RuntimeException expected) {
            assertEquals("no access", expected.getMessage());
        }
        verify(coreBatchClient, never()).report(7L);
    }
}

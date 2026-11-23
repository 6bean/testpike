package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.cluster.api.dto.ClusterBaselineScanResult;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.domain.component.ClusterVersionScanScheduler;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class ClusterVersionBaselineScanTest {

    @Autowired
    private ClusterVersionBaselineService baselineService;

    @Autowired
    private ClusterVersionScanScheduler scanScheduler;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private AppautoEngineService engineService;

    @Autowired
    private ClusterComponentService componentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long executorId;
    private String node;

    @BeforeEach
    void registerAndClean() {
        executorId = executorService.register(new ExecutorRegisterInput(
                "t319b-scan-node", null, null, "web,app", "chromium", 2, "1.4.0", 0,
                Map.of("hostOs", "linux",
                        "runtimes", Map.of("node", "22.23.1"),
                        "engines", List.of("MOBILEWRIGHT"))));
        node = String.valueOf(executorId);
        cleanLedger();
    }

    @AfterEach
    void cleanAll() {
        cleanLedger();
        jdbcTemplate.update("DELETE FROM tp_executor WHERE id = ?", executorId);
    }

    private void cleanLedger() {
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", node);
    }

    
    private long registerWithEngines(List<String> engines) {
        jdbcTemplate.update("UPDATE tp_executor SET capabilities = ?::jsonb, status = 1, "
                        + "last_heartbeat = now() WHERE id = ?",
                "{\"runtimes\": {\"node\": \"22.23.1\", \"python\": \"3.11.9\", \"java\": \"17.0.2\"},"
                        + " \"engines\": " + toJsonArray(engines) + "}",
                executorId);
        return executorId;
    }

    private static String toJsonArray(List<String> values) {
        return "[" + String.join(",", values.stream().map(v -> "\"" + v + "\"").toList()) + "]";
    }

    @Test
    void onlineExecutor_reportedEngine_runtimeReq_landsAsBaselineAndRejudges() {
        componentService.upsert(node, "engine-runtime.node", "22.23.1", "heartbeat", null, null);

        ClusterBaselineScanResult result = baselineService.syncFromDescriptors();

        assertTrue(result.nodesScanned() >= 1, "在线执行器必须进扫描面");
        List<ClusterComponentInfo> rows = componentService.listByNode(node);
        ClusterComponentInfo row = rows.stream()
                .filter(r -> "engine-runtime.node".equals(r.componentFamily())).findFirst().orElseThrow();
        assertEquals(">=22.12", row.requirement(), "MOBILEWRIGHT 描述符 node 区间必须刷进台账基准");
        assertEquals("22.23.1", row.currentVersion(), "扫描只更基准，禁丢心跳上报的现值");
        assertEquals("scan", row.source(), "基准回写来源标 scan");
        assertEquals("UNKNOWN", row.supportState(), "无金版本基准=灰诚实，禁猜绿");
        assertTrue(row.stateReason().contains("基准"), "灰理由必须人话点名无基准");
    }

    @Test
    void twoEnginesSameRuntimeKey_stricterRequirementWins() {
        registerWithEngines(List.of("MOBILEWRIGHT", "APPIUM"));
        componentService.upsert(node, "engine-runtime.node", "22.23.1", "heartbeat", null, null);

        baselineService.syncFromDescriptors();

        ClusterComponentInfo row = componentService.listByNode(node).stream()
                .filter(r -> "engine-runtime.node".equals(r.componentFamily())).findFirst().orElseThrow();
        assertEquals(">=22.12", row.requirement(), "node 区间两引擎 >=22.12 与 >=20 并存必须取严档");
    }

    @Test
    void missingLedgerRowForRequiredRuntime_createsGrayUnreportedRow() {
        baselineService.syncFromDescriptors();

        List<ClusterComponentInfo> rows = componentService.listByNode(node);
        ClusterComponentInfo row = rows.stream()
                .filter(r -> "engine-runtime.node".equals(r.componentFamily())).findFirst().orElseThrow();
        assertNull(row.currentVersion(), "建档行现值=未上报（诚实空）");
        assertEquals(">=22.12", row.requirement());
        assertEquals("UNKNOWN", row.supportState());
        assertTrue(row.stateReason().contains("未上报"), "灰理由必须人话点名未上报");
    }

    @Test
    void offlineExecutor_notScanned_baselineStaysNull() {
        componentService.upsert(node, "engine-runtime.node", "22.23.1", "heartbeat", null, null);
        jdbcTemplate.update("UPDATE tp_executor SET status = 3 WHERE id = ?", executorId);

        baselineService.syncFromDescriptors();

        ClusterComponentInfo row = componentService.listByNode(node).stream()
                .filter(r -> "engine-runtime.node".equals(r.componentFamily())).findFirst().orElseThrow();
        assertNull(row.requirement(), "离线执行器不入扫描面，基准不得误刷");
    }

    @Test
    void requirementKeyWithoutFamily_skippedHonestly_noRowCreated() {
        registerWithEngines(List.of("MAESTRO"));

        ClusterBaselineScanResult result = baselineService.syncFromDescriptors();

        assertTrue(result.skippedRequirementKeys().contains("jdk"), "无登记族的键必须列进跳过留痕");
        assertEquals(0, componentService.listByNode(node).size(), "无族可依的键禁造台账行");
    }

    @Test
    void schedulerScan_directCall_landsBaselineThroughRealChain() {
        componentService.upsert(node, "engine-runtime.node", "22.23.1", "heartbeat", null, null);

        scanScheduler.syncBaselines();

        ClusterComponentInfo row = componentService.listByNode(node).stream()
                .filter(r -> "engine-runtime.node".equals(r.componentFamily())).findFirst().orElseThrow();
        assertEquals(">=22.12", row.requirement(), "调度件直调必须真链落盘（吞异常≠PASS，锚点回 DB）");
    }

    @Test
    void enabledEngineCatalog_isRealSeedData_anchorSanity() {
        assertTrue(engineService.listEngines(false).stream()
                .anyMatch(e -> "MOBILEWRIGHT".equals(e.code())), "V145 种子必须在线");
        ExecutorInfo info = executorService.get(executorId);
        assertEquals(1, info.status(), "注册后必须在线（扫描面口径前提）");
    }
}

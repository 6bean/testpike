package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ClusterUpstreamProbeResult;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateDetectedInput;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateInfo;
import com.sixbean.testpike.core.cluster.domain.component.ClusterVersionScanScheduler;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class ClusterUpstreamProbeTest {

    private static HttpServer stub;
    private static int stubPort;

    static {
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", ClusterUpstreamProbeTest::route);
            stub.setExecutor(null);
            stub.start();
            stubPort = stub.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("loopback 上游夹具桩启动失败", e);
        }
    }

    @Autowired
    private ClusterUpstreamProbeService probeService;

    @Autowired
    private ReleaseCandidateService candidateService;

    @Autowired
    private ClusterVersionScanScheduler scanScheduler;

    @Autowired
    private ClusterComponentService componentService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String NODE = "t319c-probe-node";
    private static final String FEED_REPO = "fixture/testpike-release";

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void probeBases(DynamicPropertyRegistry registry) {
        registry.add("testpike.cluster.probe.npm-base",
                () -> "http://127.0.0.1:" + stubPort + "/npm-registry");
        registry.add("testpike.cluster.probe.pypi-base",
                () -> "http://127.0.0.1:" + stubPort);
        registry.add("testpike.cluster.probe.github-base",
                () -> "http://127.0.0.1:" + stubPort + "/github-api");
        registry.add("testpike.cluster.release-feed.repo", () -> FEED_REPO);
    }

    private static void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        int code = 200;
        String body = "{}";
        if (path.equals("/npm-registry/appium")) {
            body = "{\"dist-tags\":{\"latest\":\"2.41.0\"}}";
        } else if (path.equals("/pypi/minium/json")) {
            body = "{\"info\":{\"version\":\"1.6.9\"}}";
        } else if (path.equals("/github-api/repos/nodejs/node/releases/latest")) {
            body = "{\"tag_name\":\"v23.5.0\"}";
        } else if (path.equals("/github-api/repos/mobile-dev-inc/maestro/releases/latest")) {
            body = "{\"tag_name\":\"v1.40.0\"}";
        } else if (path.equals("/github-api/repos/" + FEED_REPO + "/releases/latest")) {
            body = "{\"tag_name\":\"v9.9.9\",\"name\":\"testPike 9.9.9\","
                    + "\"body\":\"官方发布说明：升级中心候选样例正文\","
                    + "\"published_at\":\"2026-10-12T08:00:00Z\",\"prerelease\":false,"
                    + "\"assets\":[{\"name\":\"core.jar\"},{\"name\":\"admin.jar\"}]}";
        } else if (path.equals("/github-api/repos/fixture/dead-release/releases/latest")) {
            code = 500;
            body = "boom";
        } else if (path.equals("/github-api/repos/fixture/drift-release/releases/latest")) {
            body = "{\"unexpected\":\"shape\"}";
        } else if (path.equals("/mirror/npm/appium")) {
            body = "{\"dist-tags\":{\"latest\":\"99.0.0\"}}";
        } else if (path.startsWith("/dead/")) {
            code = 500;
            body = "boom";
        } else {
            code = 404;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @BeforeEach
    void cleanLedger() {
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id <> ?", NODE);
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", NODE);
        jdbcTemplate.update("DELETE FROM tp_release_candidate");
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = 'RELEASE_CANDIDATE'");
        jdbcTemplate.update("DELETE FROM tp_release_manifest WHERE version = '9.9.9'");
        configCacheHolder.remove("installkit.mirror-base-url");
        ReflectionTestUtils.setField(probeService, "releaseFeedRepo", FEED_REPO);
        ReflectionTestUtils.setField(probeService, "releaseFeedToken", "");
    }

    @AfterEach
    void cleanAfter() {
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id <> ?", NODE);
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", NODE);
        jdbcTemplate.update("DELETE FROM tp_release_candidate");
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = 'RELEASE_CANDIDATE'");
        jdbcTemplate.update("DELETE FROM tp_release_manifest WHERE version = '9.9.9'");
        configCacheHolder.remove("installkit.mirror-base-url");
    }

    private ClusterComponentInfo row(String family) {
        List<ClusterComponentInfo> rows = componentService.listByNode(NODE).stream()
                .filter(r -> r.componentFamily().equals(family)).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Counts RELEASE_CANDIDATE notifications raised by this test's probe fixture only.
     * Scoped by dedup_key prefix (RELEASE_CANDIDATE:{version}:...) so the live
     * clusterReleaseCheck cron, which fans out concurrently during batch windows,
     * cannot pollute the global event_type count with other versions.
     */
    private int releaseCandidateNotifications() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification"
                        + " WHERE event_type = 'RELEASE_CANDIDATE'"
                        + " AND dedup_key LIKE 'RELEASE_CANDIDATE:9.9.9%'",
                Integer.class);
        return count == null ? 0 : count;
    }

    
    @Test
    void probeWritesUpstreamTargetAndRejudges() {
        componentService.upsert(NODE, "engine-runtime.node", "22.23.1", "heartbeat", ">=22.12", null);
        componentService.upsert(NODE, "engine-runtime.minium", "1.6.0", "heartbeat", ">=1.5", null);
        componentService.upsert(NODE, "engine-runtime.maestro", "1.39.0", "heartbeat", null, null);

        ClusterUpstreamProbeResult result = probeService.probeTargets();

        assertEquals(3, result.familiesProbed(), "node/minium/maestro 三族各探测一次（同族多行共享一帧上游）");
        assertEquals("23.5.0", row("engine-runtime.node").targetVersion(), "node=GitHub releases tag 去 v 前缀");
        assertEquals("1.6.9", row("engine-runtime.minium").targetVersion(), "minium=PyPI info.version");
        assertEquals("1.40.0", row("engine-runtime.maestro").targetVersion(), "maestro=GitHub releases tag 去 v 前缀");
        for (String family : List.of("engine-runtime.node", "engine-runtime.minium",
                "engine-runtime.maestro")) {
            assertEquals("YELLOW", row(family).supportState(), family + " 落后基准应判黄可升级");
        }
        assertEquals("22.23.1", row("engine-runtime.node").currentVersion(),
                "探测只更 target，禁丢心跳现值");
        assertEquals(0, result.candidatesRecorded(), "纯引擎台账=release feed 不触发（禁逐轮多余外呼）");
    }

    
    @Test
    void currentEqualToUpstreamJudgesGreen() {
        componentService.upsert(NODE, "engine-runtime.maestro", "1.40.0", "heartbeat", null, null);
        probeService.probeTargets();
        assertEquals("GREEN", row("engine-runtime.maestro").supportState());
    }

    
    @Test
    void unreachableFeedKeepsGrayAndReportsHonestly() {
        componentService.upsert(NODE, "engine-runtime.appium", "2.0.0", "heartbeat", ">=2.0", null);
        configCacheHolder.put("installkit.mirror-base-url", "http://127.0.0.1:" + stubPort + "/dead");

        ClusterUpstreamProbeResult result = probeService.probeTargets();

        ClusterComponentInfo r = row("engine-runtime.appium");
        assertNull(r.targetVersion(), "探测失败禁写 target（更禁拿旧值冒充）");
        assertEquals("UNKNOWN", r.supportState());
        assertTrue(r.stateReason().contains("未探测或外网探测不可达"),
                "判定理由应含诚实标注，实际=" + r.stateReason());
        assertTrue(result.unresolvedNotes().stream().anyMatch(n ->
                        n.startsWith("engine-runtime.appium:") && n.contains("外网探测不可达")),
                "结果应留痕失败族与原因，实际=" + result.unresolvedNotes());
        assertEquals(0, result.rowsWritten());
    }

    
    @Test
    void familyWithoutFeedSourceIsHonestUnprobed() {
        componentService.upsert(NODE, "engine-runtime.wx_devtools", "1.06.2408", "heartbeat", null, null);
        componentService.upsert(NODE, "engine-runtime.hdc", "3.1.0", "heartbeat", null, null);
        ClusterUpstreamProbeResult result = probeService.probeTargets();
        assertNull(row("engine-runtime.wx_devtools").targetVersion());
        assertTrue(result.unresolvedNotes().stream().anyMatch(n ->
                        n.startsWith("engine-runtime.wx_devtools:") && n.contains("未探测")),
                "无源族应留痕「未探测」，实际=" + result.unresolvedNotes());
        assertTrue(result.unresolvedNotes().stream().anyMatch(n ->
                        n.startsWith("engine-runtime.hdc:") && n.contains("未探测")),
                "实际=" + result.unresolvedNotes());
    }

    
    @Test
    void platformFamiliesReroutedToCandidateLedger() {
        componentService.upsert(NODE, "core", "1.2.0", "manual", null, null);
        componentService.upsert(NODE, "browser-kernel", "140.0", "manual", null, null);

        ClusterUpstreamProbeResult result = probeService.probeTargets();

        assertEquals(1, result.candidatesRecorded(),
                "命中 platform 台账族=并一次 release-feed 探测（禁逐族外呼），落一帧 detected 候选");
        ReleaseCandidateInfo candidate = candidateService.findByVersion("9.9.9");
        assertNotNull(candidate, "探测结果写候选表 tp_release_candidate（感知面）");
        assertEquals("detected", candidate.status());
        assertEquals("poll", candidate.detectedSource(), "探测面检出来源=poll");
        assertEquals("released", candidate.channel(), "prerelease 旗标 false=released 通道");
        assertTrue(candidate.payloadJson().contains("发布说明"), "changelog 人话原文直透 payload");
        assertTrue(candidate.payloadJson().contains("core.jar"), "工件名数组入 payload（T353 供给链消费源）");
        assertNull(row("core").targetVersion(), "台账 platform 族 target 保持 null（转正=T351 确认后的事）");
        assertNull(row("browser-kernel").targetVersion(), "infra 族（browser-kernel）仍静默跳零外呼");
        assertTrue(result.unresolvedNotes().stream().noneMatch(n -> n.contains("core:")),
                "platform 族命中成功属设计内，不进失败留痕（禁噪音淹没真失败）");
        Integer manifestRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_release_manifest WHERE version = '9.9.9'", Integer.class);
        assertEquals(0, manifestRows, "权威表禁探测直写（红线：发布=人工权威，两态模型）");
    }

    
    @Test
    void mirrorKeyReroutesLive() {
        componentService.upsert(NODE, "engine-runtime.appium", "2.0.0", "heartbeat", ">=2.0", null);

        ClusterUpstreamProbeResult first = probeService.probeTargets();
        assertEquals(1, first.rowsWritten());
        assertEquals("2.41.0", row("engine-runtime.appium").targetVersion(), "无镜像键=官方 npm 源");

        configCacheHolder.put("installkit.mirror-base-url",
                "http://127.0.0.1:" + stubPort + "/mirror");
        ClusterUpstreamProbeResult second = probeService.probeTargets();
        assertEquals(1, second.rowsWritten());
        assertEquals("99.0.0", row("engine-runtime.appium").targetVersion(),
                "镜像键即改即生效=探测面走镜像改道非官方源");
    }

    
    @Test
    void schedulerProbeWritesRealChain() {
        componentService.upsert(NODE, "engine-runtime.node", "22.23.1", "heartbeat", ">=22.12", null);
        scanScheduler.probeUpstreamTargets();
        assertEquals("23.5.0", row("engine-runtime.node").targetVersion(),
                "调度入口=同一探测链，锚点必须回 DB");
    }

    
    @Test
    void emptyLedgerProbesNothing() {
        ClusterUpstreamProbeResult result = probeService.probeTargets();
        assertEquals(0, result.familiesProbed());
        assertEquals(0, result.rowsWritten());
        assertEquals(0, result.candidatesRecorded(), "空台账=platform 族未命中=release feed 不外呼");
    }


    
    @Test
    void releaseFeedUnreachableKeepsStickyNoCandidateWrite() {
        candidateService.recordDetected(
                new ReleaseCandidateDetectedInput("8.8.8", "released", "poll", "{}"));
        ReflectionTestUtils.setField(probeService, "releaseFeedRepo", "fixture/dead-release");

        ClusterUpstreamProbeResult result = probeService.probeReleaseFeed();

        assertEquals(0, result.candidatesRecorded(), "不可达=零候选写");
        assertNull(candidateService.findByVersion("9.9.9"), "不可达禁落新候选（粘滞=下轮自愈）");
        assertTrue(result.unresolvedNotes().stream().anyMatch(n -> n.contains("外网探测不可达")),
                "分型留痕=外网探测不可达，实际=" + result.unresolvedNotes());
        assertEquals("detected", candidateService.findByVersion("8.8.8").status(),
                "既有候选不受失败探测扰动");
    }

    
    @Test
    void releaseFeedStructuralDriftDoesNotGuess() {
        ReflectionTestUtils.setField(probeService, "releaseFeedRepo", "fixture/drift-release");

        ClusterUpstreamProbeResult result = probeService.probeReleaseFeed();

        assertEquals(0, result.candidatesRecorded());
        assertNull(candidateService.findByVersion("9.9.9"));
        assertTrue(result.unresolvedNotes().stream().anyMatch(n -> n.contains("结构漂移")),
                "分型留痕=结构漂移不猜，实际=" + result.unresolvedNotes());
    }

    
    @Test
    void releaseFeedBlankRepoIsExplicitlyDisabled() {
        ReflectionTestUtils.setField(probeService, "releaseFeedRepo", "   ");

        ClusterUpstreamProbeResult result = probeService.probeReleaseFeed();

        assertEquals(0, result.candidatesRecorded());
        assertTrue(result.unresolvedNotes().stream().anyMatch(n -> n.contains("显式关闭")),
                "分型留痕=显式关闭不探测，实际=" + result.unresolvedNotes());
    }

    
    @Test
    void releaseCandidateNotifiedOnlyOnFreshDetected() {
        componentService.upsert(NODE, "core", "1.2.0", "manual", null, null);

        probeService.probeTargets();
        // Full-module runs seed one admin per Spring context into the shared dev DB,
        // so one fanout round writes one row per admin (dedup_key carries adminId).
        // Assert the capability, not the admin-set size: fresh detection fans out ≥1,
        // and every later probe grows the ledger by zero (dedup broken = +N rows).
        int afterFirst = releaseCandidateNotifications();
        assertTrue(afterFirst >= 1, "首次检出=扇出系统管理员待确认提醒（每管理员一行），实际=" + afterFirst);
        assertTrue(NotificationEventDict.all().containsKey("RELEASE_CANDIDATE"),
                "事件入 NotificationEventDict（偏好矩阵/卡片白名单单一来源）");

        probeService.probeTargets();
        assertEquals(afterFirst, releaseCandidateNotifications(),
                "同日同版本重探=dedup 合并（RELEASE_CANDIDATE:{version}:{日窗}:{adminId} 不刷屏）");

        jdbcTemplate.update("UPDATE tp_release_candidate SET status = 'dismissed'"
                + " WHERE version = '9.9.9'");
        probeService.probeTargets();
        assertEquals(afterFirst, releaseCandidateNotifications(),
                "已处置候选重探不再发（confirmed/dismissed/replaced=人已知悉）");
    }

    
    @Test
    void schedulerReleaseCheckWritesCandidateRealChain() {
        scanScheduler.releaseCheckCandidates();
        ReleaseCandidateInfo candidate = candidateService.findByVersion("9.9.9");
        assertNotNull(candidate, "调度入口=同一候选探测链，锚点必须回 DB");
        assertEquals("detected", candidate.status());
    }
}

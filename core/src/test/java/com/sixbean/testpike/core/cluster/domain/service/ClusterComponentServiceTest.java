package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ClusterComponentServiceTest {

    @Autowired
    private ClusterComponentService componentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanLedger() {
        jdbcTemplate.execute("DELETE FROM tp_cluster_component");
    }

    @Test
    void upsert_idempotentSingleRow_reReportBumpsCurrent_basisSticky() {
        ClusterComponentInfo first = componentService.upsert(
                "node-t797-a", "engine-runtime.mobilecli", "0.9.1", "heartbeat", ">=0.9.0", "0.9.1");
        assertEquals("GREEN", first.supportState(), "区间内且追平金版本=绿");

        componentService.upsert("node-t797-a", "engine-runtime.mobilecli", "0.9.0", "heartbeat", null, null);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cluster_component WHERE node_id = 'node-t797-a'", Integer.class);
        assertEquals(1, rows, "node×family 唯一，重报禁新建第二行");

        List<ClusterComponentInfo> views = componentService.listByNode("node-t797-a");
        assertEquals("0.9.0", views.get(0).currentVersion(), "重报必须覆盖现值");
        assertEquals(">=0.9.0", views.get(0).requirement(), "上报方不带基准时必须保留原基准（粘滞）");
        assertEquals("YELLOW", views.get(0).supportState(), "落后金版本 0.9.1=黄（可升级）");
    }

    @Test
    void red_whenCurrentOutsideSupportMatrix_failClosedEvenIfEqualsTarget() {
        ClusterComponentInfo info = componentService.upsert(
                "node-t797-b", "executor-agent", "0.8.0", "heartbeat", ">=0.9.0", "0.8.0");
        assertEquals("RED", info.supportState(), "不在支持矩阵=失守红（即便等于目标值也不得判绿）");
        assertTrue(info.stateReason().contains("支持度失守"), "人话理由须说明失守：" + info.stateReason());
    }

    @Test
    void unknown_whenNoVersionReported_honestGray() {
        ClusterComponentInfo info = componentService.upsert(
                "node-t797-c", "core", null, "heartbeat", null, "5.1.0");
        assertEquals("UNKNOWN", info.supportState(), "无实测上报=灰（诚实未知，禁按基准冒充）");
        assertTrue(info.stateReason().contains("未上报"), "灰态理由须含未上报标注：" + info.stateReason());
        assertNotNull(info.id(), "灰态也要落台账行（离线部件可见才叫全景矩阵）");

        ClusterComponentInfo later = componentService.upsert(
                "node-t797-c", "core", "5.1.0", "heartbeat", null, null);
        assertEquals("GREEN", later.supportState(), "上报补齐且追平金版本=绿");
    }

    @Test
    void unknown_whenNoTargetBaseline_refusesUpgradeVerdict() {
        ClusterComponentInfo info = componentService.upsert(
                "node-t797-d", "browser-kernel", "1.49.0", "heartbeat", ">=1.40.0", null);
        assertEquals("UNKNOWN", info.supportState(),
                "满足区间但无金版本/上游最新基准=灰（外网探测不可达或未探测，禁猜绿禁谎报可升级）");
        assertTrue(info.stateReason().contains("基准"), "灰态理由须指向缺基准：" + info.stateReason());
    }

    @Test
    void requirement_withoutGeOperator_meansEquality() {
        ClusterComponentInfo exact = componentService.upsert(
                "node-t797-e", "engine-runtime.minium", "1.6.1", "heartbeat", "1.6.1", "1.6.1");
        assertEquals("GREEN", exact.supportState(), "相等语义且版本一致=绿");

        ClusterComponentInfo over = componentService.upsert(
                "node-t797-f", "engine-runtime.minium", "1.6.2", "heartbeat", "1.6.1", "1.6.1");
        assertEquals("RED", over.supportState(), "钉版语义下版本漂移=不在矩阵=红");
    }

    @Test
    void versionCompare_padsShortSegments() {
        ClusterComponentInfo info = componentService.upsert(
                "node-t797-g", "engine-runtime.node", "22.12.0", "heartbeat", ">=22.12", "22.12");
        assertEquals("GREEN", info.supportState(), "22.12.0 与 22.12 短位补零应判相等");
    }

    @Test
    void unknownFamily_failClosedHonestListing() {
        ClusterComponentInfo info = componentService.upsert(
                "node-t797-h", "hologram-driver", "1.0.0", "heartbeat", null, "1.0.0");
        assertEquals("UNKNOWN", info.supportState(), "未知部件族无支持基准可依=fail-closed 灰，禁绿");
        assertTrue(info.stateReason().contains("未知部件族"), "理由须诚实标注族不在登记表：" + info.stateReason());
    }

    // Core single-point homepage resolution: engine join / static table / honest empty.
    @Test
    void homepageUrl_engineFamilyJoinsEngineCatalog_singleSource() {
        componentService.upsert("node-t395-a", "engine-runtime.appium", "3.2.0", "heartbeat", ">=3.0", "3.2.0");
        ClusterComponentInfo got = componentService.listByNode("node-t395-a").get(0);
        assertEquals("https://appium.io", got.homepageUrl(),
                "引擎族官网单源=tp_appauto_engine.install_doc_url，前端禁另养一份表");
    }

    @Test
    void homepageUrl_runtimeFamiliesFromStaticTable() {
        componentService.upsert("node-t395-b", "engine-runtime.node", "22.12.0", "heartbeat", ">=22.12", "22.12.0");
        componentService.upsert("node-t395-b", "engine-runtime.mobilecli", "0.9.1", "heartbeat", ">=0.9.0", "0.9.1");
        componentService.upsert("node-t395-b", "engine-runtime.jdk", "21.0.5", "manual", null, null);
        java.util.Map<String, String> byFamily = new java.util.TreeMap<>();
        for (ClusterComponentInfo row : componentService.listByNode("node-t395-b")) {
            byFamily.put(row.componentFamily(), row.homepageUrl());
        }
        assertEquals("https://nodejs.org", byFamily.get("engine-runtime.node"),
                "node 运行时走 core 静态映射表（已实证官方站）");
        assertEquals("https://github.com/mobile-next/mobilecli", byFamily.get("engine-runtime.mobilecli"),
                "mobilecli 非引擎码=静态表谱");
        assertEquals("https://adoptium.net", byFamily.get("engine-runtime.jdk"),
                "JDK 运行时走 adoptium 官方站");
    }

    @Test
    void homepageUrl_selfBuiltBlankCatalogAndUnknownFamilies_stayEmpty() {
        ClusterComponentInfo agent = componentService.upsert(
                "node-t395-c", "executor-agent", "0.9.0", "heartbeat", null, "0.9.0");
        assertEquals("executor-agent", agent.componentFamily());
        java.util.Map<String, String> byFamily = new java.util.TreeMap<>();
        for (ClusterComponentInfo row : componentService.listByNode("node-t395-c")) {
            byFamily.put(row.componentFamily(), row.homepageUrl());
        }
        org.junit.jupiter.api.Assertions.assertNull(byFamily.get("executor-agent"),
                "自研件不算第三方快速入口（留空防自指噪音）");

        // HYPIUM catalog row exists with a blank install_doc_url = honest empty, no fallback invention
        ClusterComponentInfo blankCatalog = componentService.upsert(
                "node-t395-c", "engine-runtime.hypium", "1.0.0", "heartbeat", null, null);
        org.junit.jupiter.api.Assertions.assertNull(blankCatalog.homepageUrl(),
                "目录行 URL 为空=诚实缺省，禁瞎填");

        ClusterComponentInfo platform = componentService.upsert(
                "node-t395-c", "core", "5.1.0", "heartbeat", null, null);
        org.junit.jupiter.api.Assertions.assertNull(platform.homepageUrl(),
                "platform 自研族无官网语义=空");

        ClusterComponentInfo unknown = componentService.upsert(
                "node-t395-c", "hologram-driver", "1.0.0", "heartbeat", null, null);
        org.junit.jupiter.api.Assertions.assertNull(unknown.homepageUrl(),
                "未知族解析不出=空（灰「—」禁死链在前端渲染面断）");
    }

    @Test
    void blankNodeOrFamily_rejectedWithHumanMessage() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> componentService.upsert("  ", "core", "1.0", "heartbeat", null, null));
        assertTrue(e1.getMessage().contains("节点标识"), e1.getMessage());
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> componentService.upsert("node-t797-i", null, "1.0", "heartbeat", null, null));
        assertTrue(e2.getMessage().contains("部件族"), e2.getMessage());
    }

    @Test
    void listByNode_onlyOwnRows() {
        componentService.upsert("node-t797-j", "core", "5.1.0", "heartbeat", null, "5.1.0");
        componentService.upsert("node-t797-k", "core", "5.0.0", "scan", null, "5.1.0");
        List<ClusterComponentInfo> mine = componentService.listByNode("node-t797-j");
        assertEquals(1, mine.size(), "按节点过滤禁串台");
        assertEquals("heartbeat", mine.get(0).source());
        List<ClusterComponentInfo> all = componentService.listAll();
        assertTrue(all.size() >= 2, "全景面至少两节点两行");
    }
}

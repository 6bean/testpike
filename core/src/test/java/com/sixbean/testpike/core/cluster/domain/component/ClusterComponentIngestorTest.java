package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@SpringBootTest(classes = CoreApplication.class)
class ClusterComponentIngestorTest {

    @Autowired
    private ClusterComponentIngestor ingestor;

    @Autowired
    private ClusterComponentService componentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

 
    private static final String NODE = "880799";

    @BeforeEach
    @AfterEach
    void cleanNode() {
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", NODE);
    }

    @Test
    void ingest_normalRows_landedAsHeartbeatSource() {
        ingestor.ingest(NODE, List.of(
                Map.of("family", "engine-runtime.node", "currentVersion", "v22.23.1"),
                Map.of("family", "executor-agent", "currentVersion", "1.4.0")));

        List<ClusterComponentInfo> rows = componentService.listByNode(NODE);
        assertEquals(2, rows.size(), "两族扩报行都必须落台账");
        for (ClusterComponentInfo r : rows) {
            assertEquals("heartbeat", r.source(), "心跳落账来源必须标 heartbeat");
            assertNotNull(r.reportedAt(), "现值有值=最近上报时间必须盖现章");
            assertNotNull(r.currentVersion(), "现值不得丢失");
        }
    }

    @Test
    void ingest_repeatedHeartbeats_idempotentSingleRow() {
        List<Map<String, Object>> report =
                List.of(Map.of("family", "engine-runtime.node", "currentVersion", "v22.23.1"));
        ingestor.ingest(NODE, report);
        ingestor.ingest(NODE, report);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cluster_component WHERE node_id = ?", Integer.class, NODE);
        assertEquals(1, rows, "15s 心跳风暴幂等：同族重报禁堆积重复行");
    }

    @Test
    void ingest_malformedRows_skipBadLandGood_neverThrows() {
        ingestor.ingest(NODE, List.of(
                "junk-not-a-map",
                Map.of("family", "", "currentVersion", "1.0.0"),
                Map.of("family", "executor-agent", "currentVersion", "   "),
                Map.of("family", "engine-runtime.node", "currentVersion", "v22.23.1")));

        List<ClusterComponentInfo> rows = componentService.listByNode(NODE);
        assertEquals(1, rows.size(), "只有合法行落账=1 行");
        assertEquals("engine-runtime.node", rows.get(0).componentFamily());
        assertEquals("v22.23.1", rows.get(0).currentVersion());
    }

    @Test
    void ingest_upsertThrowsOnOneRow_laterGoodRowsStillLand() {
        String longFamily = "engine-runtime." + "x".repeat(70);
        assertEquals(85, longFamily.length(), "构造超长族名=85>上限 80");
        ingestor.ingest(NODE, List.of(
                Map.of("family", longFamily, "currentVersion", "1.0.0"),
                Map.of("family", "engine-runtime.node", "currentVersion", "v22.23.1")));

        List<ClusterComponentInfo> rows = componentService.listByNode(NODE);
        assertEquals(1, rows.size(), "超长族行被隔离后，同批好行必须照常落账");
        assertEquals("engine-runtime.node", rows.get(0).componentFamily());
    }

    @Test
    void ingest_emptyRows_zeroLedgerWrites() {
        ingestor.ingest(NODE, List.of());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cluster_component WHERE node_id = ?", Integer.class, NODE);
        assertEquals(0, rows, "空清单=零动作（旧执行器不出键的落账面语义）");
    }
}

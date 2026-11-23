package com.sixbean.testpike.core.batch.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.batch.api.dto.BatchQuery;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class BatchEnvBannerDetailTest {

    @Autowired
    private BatchService batchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long otherEnvId;
    private long targetId;
    private long otherTargetId;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_monitor_episode WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4c%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4c%')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE name LIKE 't274p4c%'");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4c%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274p4c%'");
    }

    @BeforeEach
    void seedTopology() {
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code) VALUES ('t274p4c项目', substr(md5(random()::text), 1, 16)) "
                        + "RETURNING id", Long.class);
        envId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4c环境', 1, 'http://127.0.0.1:1') RETURNING id", Long.class, projectId);
        otherEnvId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4c他环境', 2, 'http://127.0.0.1:2') RETURNING id", Long.class, projectId);
        targetId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_monitor_target (project_id, env_id, name, target_type) "
                        + "VALUES (?, ?, 't274p4c登录链路', 'LOGIN') RETURNING id", Long.class, projectId, envId);
        otherTargetId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_monitor_target (project_id, env_id, name, target_type) "
                        + "VALUES (?, ?, 't274p4c他目标', 'HTTP') RETURNING id", Long.class, projectId, otherEnvId);
    }

    
    private long runningBatch(Long env) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, started_at) "
                        + "VALUES (?, ?, ?, 't274p4c批', 4, 1, now() - interval '30 minutes') RETURNING id",
                Long.class, projectId, env, "B-" + System.nanoTime());
        assert batchId != null;
        return batchId;
    }

    private void insertEpisode(long target, String startedOffset, String recoveredOffset, String cause) {
        jdbcTemplate.update(
                "INSERT INTO tp_monitor_episode (target_id, started_at, recovered_at, failure_count, cause_summary) "
                        + "VALUES (?, now() - interval '" + startedOffset + "', "
                        + (recoveredOffset == null ? "NULL" : "now() - interval '" + recoveredOffset + "'")
                        + ", 7, ?)", target, cause);
    }


    @Test
    void 环境进行中红灯_批次详情引用episode供横幅() {
        insertEpisode(targetId, "12 minutes", null, "SSO 回调 502");
        long batchId = runningBatch(envId);

        BatchInfo info = batchService.get(batchId);

        assertNotNull(info.envOngoingEpisode(), "进行中红灯必须进详情横幅字段");
        assertEquals("t274p4c登录链路", info.envOngoingEpisode().targetName());
        assertEquals("LOGIN", info.envOngoingEpisode().targetType());
        assertTrue(info.envOngoingEpisode().ongoing(), "横幅只跟进行中事件");
        assertNotNull(info.envOngoingEpisode().startedAt(), "红时长分钟数由 startedAt 前端/文案层派生");
        assertEquals("SSO 回调 502", info.envOngoingEpisode().causeSummary());
    }


    @Test
    void episode已恢复_详情横幅字段为空() {
        insertEpisode(targetId, "2 hours", "10 minutes", "已恢复的旧故障");
        long batchId = runningBatch(envId);

        assertNull(batchService.get(batchId).envOngoingEpisode(), "恢复的红不再吓人（只引用进行中）");
    }


    @Test
    void 异环境进行中红灯_不混入本批详情() {
        insertEpisode(otherTargetId, "30 minutes", null, "他环境故障");
        long batchId = runningBatch(envId);

        assertNull(batchService.get(batchId).envOngoingEpisode(), "横幅按批所属环境圈定，禁跨环境误挂");
    }


    @Test
    void 无环境批_详情横幅字段为空不炸() {
        long batchId = runningBatch(null);

        BatchInfo info = batchService.get(batchId);
        assertNull(info.envOngoingEpisode(), "无环境批=无横幅事实可引用（禁查全库误挂）");
    }


    @Test
    void 列表视图不附横幅字段_详情专属语义() {
        insertEpisode(targetId, "15 minutes", null, "登录链路持续 DOWN");
        runningBatch(envId);

        PageResult<BatchInfo> page = batchService.list(new BatchQuery(projectId, null, null, 1, 20));
        List<BatchInfo> rows = page.list();
        assertEquals(1, rows.size(), "夹具批应在列表默认视图（type=1 正式批）");
        assertNull(rows.get(0).envOngoingEpisode(), "列表禁逐行附带进行中事件（N+1；横幅=详情页专属）");
    }
}

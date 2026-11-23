package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class BatchFeishuRoutingTest {

    @Autowired
    private BatchFailureNotifier batchFailureNotifier;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer server;
    private int port;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger receivedCount = new AtomicInteger();

    /** Per-run token: only this run's projects/notifications are swept and counted (R37). */
    private final String token = SharedDbFixtureSweep.newToken();
    private final List<Long> projectIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    @BeforeEach
    void startStub() throws IOException {
        receivedBodies.clear();
        receivedCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedCount.incrementAndGet();
            byte[] resp = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopStubAndReset() {
        if (server != null) {
            server.stop(0);
        }
        for (String key : List.of("notify.feishu.enabled", "notify.feishu.webhook",
                "notify.feishu.secret", "notify.web.base-url")) {
            configCacheHolder.remove(key);
            jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", key);
        }
    }

    private void setConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES (?, ?, '通知', 'string', 0, '批次飞书路由测试配置')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value);
        configCacheHolder.put(key, value);
    }

    private long seededProjectId;

    
    private long seedFailureBatch(int projectNotify, int batchNotify) {
        Long projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, test_owner_id) VALUES (?,"
                        + " 'RT-' || (extract(epoch from now())*10000)::bigint, 1) RETURNING id",
                Long.class, token + "-路由矩阵项目");
        assert projectId != null;
        seededProjectId = projectId;
        projectIds.add(projectId);
        jdbcTemplate.update("UPDATE tp_project SET notify_feishu = ? WHERE id = ?", projectNotify, projectId);
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type, notify_feishu)"
                        + " VALUES (?, ?, '矩阵批次', 7, 1, ?) RETURNING id",
                Long.class, projectId, "MB-" + System.nanoTime(), batchNotify);
        assert batchId != null;
        Long caseId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_test_case (project_id, case_no, title) VALUES (?, ?, '会挂的用例') RETURNING id",
                Long.class, projectId, "C-" + System.nanoTime());
        assert caseId != null;
        jdbcTemplate.update("INSERT INTO tp_batch_case (batch_id, case_id, result) VALUES (?, ?, 2)",
                batchId, caseId);
        return batchId;
    }

    private void enableGlobal() {
        setConfig("notify.feishu.enabled", "1");
        setConfig("notify.feishu.webhook", "http://127.0.0.1:" + port + "/hook");
    }


    @Test
    void 批开项目关_发() throws InterruptedException {
        enableGlobal();
        long batchId = seedFailureBatch(0, 1);

        batchFailureNotifier.notifyPartialFailed(batchId);

        Thread.sleep(1200);
        assertEquals(1, receivedCount.get(), "定案D：批级开关=1 应发（项目回退仅批=0 时生效）");
        assertTrue(receivedBodies.get(0).contains("矩阵批次"), "消息含批次名");
    }

    @Test
    void 批关项目开_回退发() throws InterruptedException {
        enableGlobal();
        long batchId = seedFailureBatch(1, 0);

        batchFailureNotifier.notifyPartialFailed(batchId);

        Thread.sleep(1200);
        assertEquals(1, receivedCount.get(), "定案D：批=0 项目=1 回退应发");
    }

    @Test
    void 批关项目关_零外呼_站内照发() throws InterruptedException {
        enableGlobal();
        long batchId = seedFailureBatch(0, 0);

        batchFailureNotifier.notifyPartialFailed(batchId);

        Thread.sleep(1200);
        assertEquals(0, receivedCount.get(), "TC-V6-01：均关=零外呼（默认行为不变）");
        Long inbox = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'BATCH_FAILED'"
                        + " AND project_id = ?",
                Long.class, seededProjectId);
        assertEquals(1, inbox, "E11：站内信主链路不受渠道开关影响（⑤共存）");
    }

    @Test
    void 全局开关关_零外呼() throws InterruptedException {
        setConfig("notify.feishu.enabled", "0");
        setConfig("notify.feishu.webhook", "http://127.0.0.1:" + port + "/hook");
        long batchId = seedFailureBatch(1, 1);

        batchFailureNotifier.notifyPartialFailed(batchId);

        Thread.sleep(1200);
        assertEquals(0, receivedCount.get(), "主开关关=任何批次开关组合零外呼");
    }

    @Test
    void 批开发_站内与飞书共存_收件人test_owner() throws InterruptedException {
        enableGlobal();
        long batchId = seedFailureBatch(0, 1);

        batchFailureNotifier.notifyPartialFailed(batchId);

        Thread.sleep(1200);
        assertEquals(1, receivedCount.get(), "飞书外呼成功");
        Long inbox = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'BATCH_FAILED' AND user_id = 1"
                        + " AND project_id = ?",
                Long.class, seededProjectId);
        assertEquals(1, inbox, "⑤：站内信与飞书共存互不影响，收件人=test_owner_id");
    }
}

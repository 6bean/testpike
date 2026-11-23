package com.sixbean.testpike.core.audit.domain.service.impl;

import com.sixbean.testpike.common.clientobject.ChainVerifyResult;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditChainService;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AuditChainServiceTest {

    @Autowired
    private AuditChainService auditChainService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

 
    private static final String DEFAULT_THRESHOLD = "100000";

    private static final String GATE_KEY = "tp.audit_retention_allowed";

    @AfterEach
    void restoreThreshold() {
        setThresholdViaConfigService(DEFAULT_THRESHOLD);
        jdbcTemplate.update("DELETE FROM tp_export_task WHERE report_type = ?",
                AuditChainServiceImpl.TASK_TYPE);
    }

    private AuditLogInput input(long i) {
        return new AuditLogInput(1L, 100L, "case", String.valueOf(i), "review",
                Map.of("status", "待评审"), Map.of("status", "已通过", "version", (int) i));
    }

    private void truncateChain() {
        jdbcTemplate.execute("TRUNCATE tp_audit_log");
        jdbcTemplate.execute("TRUNCATE tp_audit_retention_break");
        jdbcTemplate.update("DELETE FROM tp_export_task WHERE report_type = ?",
                AuditChainServiceImpl.TASK_TYPE);
    }

    
    private long tamperOldestRow() {
        Long id = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_audit_log", Long.class);
        assertNotNull(id);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SELECT set_config('" + GATE_KEY + "', 'on', true)");
            jdbcTemplate.update("UPDATE tp_audit_log SET after_json = '{\"status\":\"篡改\"}'::jsonb"
                    + " WHERE id = ?", id);
        });
        return id;
    }

 
    private void setThresholdViaConfigService(String value) {
        ConfigInfo info = configService.list("安全").stream()
                .filter(c -> AuditChainServiceImpl.KEY_THRESHOLD.equals(c.configKey()))
                .findFirst().orElseThrow(() -> new IllegalStateException("阈值键缺失（V22 未落库？）"));
        configService.update(9L, info.id(), new ConfigInput(
                info.configKey(), value, info.groupName(), info.valueType(),
                info.encryptFlag(), info.description()));
    }


    @Test
    void verify_完整链返回verdict且条数正确() {
        truncateChain();
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        auditLogService.record(input(3));

        ChainVerifyResult r = auditChainService.verify(9L);
        assertFalse(r.async(), "3 条 ≤ 默认阈值 10 万必须同步");
        assertEquals(2, r.status(), "同步完成 status=2");
        assertTrue(r.valid(), "未篡改链必须 valid=true");
        assertNull(r.brokenAtId(), "完整链无断裂行");
        assertEquals(3L, r.checkedCount(), "校验条数=3");
        assertNotNull(r.checkedAt(), "校验时间必须返回（前端绿标展示）");
    }

    @Test
    void verify_结果进审计action_verify且不含链内容() {
        truncateChain();
        auditLogService.record(input(1));
        long before = auditRowCount();

        ChainVerifyResult r = auditChainService.verify(9L);
        assertTrue(r.valid());

        long after = auditRowCount();
        assertEquals(before + 1, after, "校验动作本身必须进审计（恰 1 条）");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT object_type, action, operator_id, after_json::text AS aj FROM tp_audit_log"
                        + " WHERE action = 'verify' ORDER BY id DESC LIMIT 1");
        assertEquals("audit_chain", row.get("object_type"));
        assertEquals("verify", row.get("action"));
        assertEquals(9L, ((Number) row.get("operator_id")).longValue(), "操作人=X-Operator-Id 透传");
        String aj = (String) row.get("aj");
        assertTrue(aj.contains("\"valid\"") && aj.contains("\"checkedCount\""),
                "审计 after 必须含结论要素，实际=" + aj);
        assertFalse(aj.contains("待评审"), "审计 after 不得泄露链内容（before/after 快照值）");
    }


    @Test
    void verify_篡改行报出断裂行id() {
        truncateChain();
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        auditLogService.record(input(3));
        long brokenId = tamperOldestRow();

        ChainVerifyResult r = auditChainService.verify(9L);
        assertFalse(r.valid(), "篡改后必须检出");
        assertEquals(brokenId, r.brokenAtId(), "断裂行 id 必须精确报出（TC-R3-01「第 K 条」）");
    }

    @Test
    void verifyChain探针与结构化校验同源() {
        truncateChain();
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        assertTrue(auditLogService.verifyChain());
        assertTrue(auditChainService.verify(9L).valid());
        tamperOldestRow();
        assertFalse(auditLogService.verifyChain(), "探针（既有 L4 消费方）必须与结构化同源判断裂");
        assertFalse(auditChainService.verify(9L).valid());
    }


    @Test
    void verify_超阈值走异步任务且完成后可查结果() throws Exception {
        truncateChain();
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        setThresholdViaConfigService("1");

        ChainVerifyResult submitted = auditChainService.verify(9L);
        assertTrue(submitted.async(), "超阈值必须异步");
        assertTrue(submitted.taskId() > 0, "异步必须返回 taskId");

        ChainVerifyResult done = waitTaskFinished(submitted.taskId(), 20_000);
        assertEquals(2, done.status(), "任务应完成");
        assertTrue(done.valid(), "未篡改链异步结论=完整");
        assertTrue(done.checkedCount() >= 2L, "checkedCount=" + done.checkedCount());
        Map<String, Object> rj = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                jdbcTemplate.queryForObject(
                        "SELECT result_json::text FROM tp_export_task WHERE id = ?", String.class,
                        submitted.taskId()),
                Map.class);
        assertEquals(Boolean.TRUE, rj.get("valid"), "结果快照=" + rj);
        assertFalse(rj.containsKey("beforeJson") || String.valueOf(rj).contains("待评审"),
                "任务结果不得泄露链内容（IT-R3-01）");
        Long pid = jdbcTemplate.queryForObject(
                "SELECT project_id FROM tp_export_task WHERE id = ?", Long.class, submitted.taskId());
        assertNull(pid, "链校验任务=系统级（project_id NULL，不受项目 purge 级联）");
    }

    @Test
    void verify_异步断裂任务结论报出行id() {
        truncateChain();
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        long brokenId = tamperOldestRow();
        setThresholdViaConfigService("1");

        ChainVerifyResult submitted = auditChainService.verify(9L);
        assertTrue(submitted.async());
        ChainVerifyResult done = waitTaskFinished(submitted.taskId(), 20_000);
        assertEquals(2, done.status());
        assertFalse(done.valid(), "异步任务必须检出断裂");
        assertEquals(brokenId, done.brokenAtId(), "异步断裂同样精确报行号");
    }

    @Test
    void verify_并发双击复用进行中任务幂等() {
        truncateChain();
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        setThresholdViaConfigService("1");
        Long runningId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_export_task (project_id, report_type, masked, status, created_by)
                VALUES (NULL, ?, 0, 1, 9) RETURNING id
                """, Long.class, AuditChainServiceImpl.TASK_TYPE);

        ChainVerifyResult r = auditChainService.verify(9L);
        assertTrue(r.async());
        assertEquals(runningId, r.taskId(), "存在进行中任务必须复用（不重复起任务）");
        assertEquals(1L, exportTaskCount(), "幂等：不得新增第二条任务");
    }

    @Test
    void verify_纯读零写入除审计记录() {
        truncateChain();
        auditLogService.record(input(1));
        long auditBefore = auditRowCount();

        auditChainService.verify(9L);
        assertEquals(auditBefore + 1, auditRowCount(), "唯一写入=审计 verify 记录本身");
        assertEquals(0L, exportTaskCount(), "同步分支不得创建导出任务");
    }

    @Test
    void taskResult_不存在任务404人话() {
        truncateChain();
        ServiceException e = assertThrows(ServiceException.class,
                () -> auditChainService.taskResult(999999999L));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    // ── helpers ──

    private long auditRowCount() {
        Long c = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_audit_log", Long.class);
        return c == null ? 0 : c;
    }

    private long exportTaskCount() {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_export_task WHERE report_type = ?", Long.class,
                AuditChainServiceImpl.TASK_TYPE);
        return c == null ? 0 : c;
    }

    private ChainVerifyResult waitTaskFinished(long taskId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ChainVerifyResult r = auditChainService.taskResult(taskId);
            if (r.status() != 1) {
                return r;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("链校验异步任务 " + taskId + " 未在 " + timeoutMs + "ms 内完成");
    }
}

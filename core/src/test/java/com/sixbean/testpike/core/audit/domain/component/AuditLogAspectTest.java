package com.sixbean.testpike.core.audit.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.AuditLog;
import com.sixbean.testpike.core.support.AuditLedgerWindow;


import com.sixbean.testpike.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * <p>Shared-ledger discipline (R37): the append-only tp_audit_log is never
 * TRUNCATE'd here. Each test opens a ledger window at the current max id and
 * sweeps only its own window on teardown; fixture object ids are random per
 * run so filtered queries never collide with historical rows, and the one
 * fixed-key case (the "#result" fallback writing object_id 0) lives under the
 * fixture-exclusive "fx-plan" family that setup self-heals.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class AuditLogAspectTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Fresh random business id per test instance (JUnit 5 per-method instance). */
    private final long fxId =
            ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);

    private AuditLedgerWindow ledger;

    @Autowired
    private TestAuditService auditService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void openAuditWindow() {
        ledger = new AuditLedgerWindow(jdbcTemplate);
        ledger.open();
    }

    @AfterEach
    void sweepAuditWindow() {
        ledger.close();
    }

    @Test
    void audit注解_成功方法写库且SpEL正确() throws Exception {
        String result = auditService.reviewCase(fxId,
                Map.of("status", "待评审"), Map.of("status", "已通过", "version", 3));
        assertEquals("ok-" + fxId, result, "业务方法必须正常执行");

        List<AuditLog> logs = auditLogService.query(null, null, "case",
                String.valueOf(fxId), "review", 1, 10).list();
        assertEquals(1, logs.size(), "必须真实写入 1 条审计");
        AuditLog log = logs.get(0);
        assertEquals(String.valueOf(fxId), log.objectId(), "objectId SpEL #id 必须解析为业务主键");
        assertEquals(mapper.readTree("{\"status\":\"待评审\"}"), mapper.readTree(log.beforeJson()),
                "before SpEL 必须记录");
        assertEquals(mapper.readTree("{\"status\":\"已通过\",\"version\":3}"), mapper.readTree(log.afterJson()),
                "after SpEL 必须记录");
        assertTrue(auditLogService.verifyChain(), "切面写入后哈希链必须完整");
    }

    @Test
    void audit注解_异常方法记录error且异常继续抛出() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> auditService.failCase(fxId), "业务异常必须继续抛出（审计不吞异常）");
        assertEquals(40401, ex.getCode());

        List<AuditLog> logs = auditLogService.query(null, null, "case",
                String.valueOf(fxId), "review", 1, 10).list();
        assertEquals(1, logs.size(), "失败也必须写入审计");
        assertTrue(logs.get(0).afterJson().contains("error"), "失败记录 after 必须含 error 信息");
        assertTrue(auditLogService.verifyChain(), "失败记录后哈希链必须完整");
    }

    @Test
    void audit注解_失败路径objectId引用result回退0不WARN() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> auditService.failWithResultId(), "业务异常必须继续抛出");
        assertEquals(40001, ex.getCode());

        // The ledger form keeps historical rows, so "written exactly once" is a
        // window assertion (id > this run's watermark); crashed-run leftovers of
        // the same fixed key sit below the watermark and are excluded, and the
        // query surface (id DESC) must surface this run's row first.
        Long mine = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE id > ? AND object_type = 'fx-plan'"
                        + " AND object_id = '0' AND action = 'fx_plan_create'",
                Long.class, ledger.floor());
        assertEquals(1L, mine, "失败路径必须真实写入审计（object_id=0）而非静默丢弃");
        List<AuditLog> logs = auditLogService.query(null, null, "fx-plan", "0",
                "fx_plan_create", 1, 10).list();
        assertTrue(logs.size() >= 1, "查询面必须能读到失败审计");
        assertEquals("0", logs.get(0).objectId(), "查询首行 objectId 必须为回退值 0");
        assertTrue(logs.get(0).afterJson().contains("error"), "失败记录 after 必须含 error 信息");
        assertTrue(auditLogService.verifyChain(), "失败记录后哈希链必须完整");
    }

    @Test
    void audit注解_ThreadLocalBefore兜底记录diff且敏感打码() throws Exception {
        String result = auditService.updateWithContext(fxId, Map.of("name", "新模型"));
        assertEquals("ok-" + fxId, result, "业务方法必须正常执行");

        List<AuditLog> logs = auditLogService.query(null, null, "llm_config",
                String.valueOf(fxId), "update", 1, 10).list();
        assertEquals(1, logs.size(), "必须真实写入 1 条审计");
        AuditLog log = logs.get(0);
        var before = mapper.readTree(log.beforeJson());
        assertEquals("旧模型", before.get("name").asText(), "before 快照必须完整记录旧值（diff 完整性）");
        assertEquals("******", before.get("apiKey").asText(), "敏感字段 apiKey 必须打码");
        assertNull(AuditLogContext.takeBefore(), "切面消费后 ThreadLocal 必须清空（防线程池串扰）");
        assertTrue(auditLogService.verifyChain(), "写入后哈希链必须完整");
    }

    @Test
    void audit注解_失败路径ThreadLocalBefore也记录() throws Exception {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> auditService.failWithContext(fxId), "业务异常必须继续抛出");
        assertEquals(40002, ex.getCode());

        List<AuditLog> logs = auditLogService.query(null, null, "sys_config",
                String.valueOf(fxId), "update", 1, 10).list();
        assertEquals(1, logs.size(), "失败路径必须写入审计");
        var before = mapper.readTree(logs.get(0).beforeJson());
        assertEquals("旧值", before.get("configValue").asText(), "失败路径 before 快照必须保留");
        assertTrue(logs.get(0).afterJson().contains("error"), "失败记录 after 必须含 error");
        assertNull(AuditLogContext.takeBefore(), "失败路径 ThreadLocal 也必须清空");
    }

    @Test
    void audit注解_嵌套record含LocalDateTime_哈希链完整() {
        int fxCount = (int) (fxId % 2_000_000_000L);
        NestedCaseSnapshot result = auditService.nestedCreate(fxCount);
        assertEquals(fxCount, result.count().intValue(), "业务方法必须正常执行");
        List<AuditLog> logs = auditLogService.query(null, null, "case",
                String.valueOf(fxCount), "create", 1, 10).list();
        assertEquals(1, logs.size(), "嵌套 record 审计必须真实写入（不得被切面吞掉）");
        assertTrue(auditLogService.verifyChain(),
                "嵌套 record + LocalDateTime 审计写入后哈希链必须完整（jsonb 键排序免疫）");
    }
}

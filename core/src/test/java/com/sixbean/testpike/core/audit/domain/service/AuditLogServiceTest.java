package com.sixbean.testpike.core.audit.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.AuditLog;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.support.AuditLedgerWindow;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * <p>Shared-ledger discipline (R37): the append-only tp_audit_log is never
 * TRUNCATE'd here. Each test opens a ledger window at the current max id and
 * sweeps only its own window on teardown; fixture rows carry a per-run
 * object_type token so filtered queries and order assertions never see
 * historical or crashed-run rows, and window counts use the id watermark.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class AuditLogServiceTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Unique per-test object_type family (JUnit 5 per-method instance). */
    private final String fxType = SharedDbFixtureSweep.newToken();

    private AuditLedgerWindow window;

    @BeforeEach
    void markAuditFloor() {
        window = new AuditLedgerWindow(jdbcTemplate);
        window.open();
    }

    @AfterEach
    void releaseAuditFloor() {
        window.close();
    }

    private AuditLogInput input(long i) {
        return new AuditLogInput(1L, 100L, fxType, String.valueOf(i), "review",
                Map.of("status", "待评审"), Map.of("status", "已通过", "version", (int) i));
    }

    @Test
    void record_写库且哈希链完整() {
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        auditLogService.record(input(3));

        assertTrue(auditLogService.verifyChain(), "3 条记录哈希链必须完整");
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE id > ?", Long.class, window.floor());
        assertEquals(3L, cnt, "必须真实落库 3 条（本 run 账窗内）");
    }

    @Test
    void record_链头prevHash为64个0() {
        // The chain origin carries a zero prev_hash unless retention purged the
        // prefix; when it did, the break ledger registers the hash that
        // legitimately heads the surviving chain (AuditChainVerifier semantics).
        String expectedHead = jdbcTemplate.queryForObject(
                "SELECT coalesce((SELECT new_head_hash FROM tp_audit_retention_break"
                        + " ORDER BY removed_through_id DESC LIMIT 1), ?)",
                String.class, "0".repeat(64));
        auditLogService.record(input(1));
        String prevHash = jdbcTemplate.queryForObject(
                "SELECT prev_hash FROM tp_audit_log WHERE id = (SELECT min(id) FROM tp_audit_log)",
                String.class);
        assertEquals(expectedHead, prevHash, "链头 prev_hash 必须等于链起点（断点账本感知）");
        assertNotNull(auditLogService.verifyChain());
    }

    @Test
    void verifyChain_篡改curHash被检出() {
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        assertTrue(auditLogService.verifyChain(), "篡改前链必须完整");

        boolean updateBlocked = false;
        try {
            jdbcTemplate.update("UPDATE tp_audit_log SET cur_hash = repeat('f', 64) WHERE id = "
                    + "(SELECT min(id) FROM tp_audit_log)");
        } catch (Exception e) {
            updateBlocked = true;
        }
        assertTrue(updateBlocked, "UPDATE 必须被 append-only trigger 拒绝");
        assertTrue(auditLogService.verifyChain(), "trigger 拦截后链仍应完整");
    }

    @Test
    void verifyChain_删链中记录被检出() {
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        boolean deleteBlocked = false;
        try {
            jdbcTemplate.update("DELETE FROM tp_audit_log WHERE id = "
                    + "(SELECT min(id) FROM tp_audit_log)");
        } catch (Exception e) {
            deleteBlocked = true;
        }
        assertTrue(deleteBlocked, "DELETE 必须被 append-only trigger 拒绝");
        assertTrue(auditLogService.verifyChain(), "trigger 拦截后链仍应完整");
    }

    @Test
    void record_并发追加20条链完整不分叉() throws Exception {
        int threads = 5;
        int perThread = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        auditLogService.record(input(base + i));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发写必须 60s 内完成");
        pool.shutdown();

        assertTrue(errors.isEmpty(), "并发写不得有异常: " + errors);
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE id > ?", Long.class, window.floor());
        assertEquals(20L, cnt, "并发写入必须全部落库（本 run 账窗内）");
        assertTrue(auditLogService.verifyChain(), "并发追加后哈希链必须完整（无分叉）");
    }

    @Test
    void query_分页与筛选正确() {
        auditLogService.record(input(1));
        auditLogService.record(input(2));
        auditLogService.record(new AuditLogInput(2L, 200L, fxType, "77", "create",
                null, Map.of("name", "demo")));

        PageResult<AuditLog> all = auditLogService.query(null, null, fxType, null, null, 1, 2);
        assertEquals(3L, all.total(), "total 必须为全量 3");
        assertEquals(2, all.list().size(), "page size=2 必须返回 2 条");
        assertTrue(all.list().get(0).id() > all.list().get(1).id(), "必须按 id 倒序");

        PageResult<AuditLog> byAction = auditLogService.query(null, null, fxType, null, "review", 1, 20);
        assertEquals(2L, byAction.total(), "action=review 必须筛出 2 条");
        assertEquals(fxType, byAction.list().get(0).objectType());

        PageResult<AuditLog> byObject = auditLogService.query(null, 200L, fxType, "77", "create", 1, 20);
        assertEquals(1L, byObject.total(), "对象筛选必须精确命中");
        assertEquals(2L, byObject.list().get(0).operatorId());
    }

    @Test
    void query_分页超限拒400人话_TC_K13_02() {
        auditLogService.record(input(1));
        ValidationException e = assertThrows(ValidationException.class,
                () -> auditLogService.query(null, null, null, null, null, 0, 9999),
                "K13：size=9999 必须拒绝而非静默钳 200（旧内联双算收编）");
        assertTrue(e.getMessage().contains("单页最大 200"),
                "人话消息须含「单页最大 200」，实际=" + e.getMessage());
    }

    @Test
    void record_空快照null入链() {
        auditLogService.record(new AuditLogInput(1L, null, fxType, "1", "create", null, null));
        assertTrue(auditLogService.verifyChain(), "before/after 为 null 时必须正常入链");
    }

    // ---------- server-side sort over the audit log list ----------

    /**
     * Seed four audit rows under one per-run object_type token (so the query
     * window is exactly these four rows even on the shared ledger) whose
     * id / operator_id / object_id / action orders deliberately disagree
     * (insertion is id-ascending; row identity in assertions is object_id:
     * r1="10" r2="05" r3="30" r4="02"). created_at is monotonic with
     * insertion (the append-only trigger forbids tampering without the
     * retention gate), so created_at asc mirrors id asc.
     */
    private void seedAuditSortFixture() {
        auditLogService.record(new AuditLogInput(1L, 100L, fxType, "10", "review",
                Map.of("k", 1), Map.of("k", 2)));
        auditLogService.record(new AuditLogInput(2L, 200L, fxType, "05", "create",
                Map.of("k", 3), Map.of("k", 4)));
        auditLogService.record(new AuditLogInput(3L, 300L, fxType, "30", "update",
                Map.of("k", 5), Map.of("k", 6)));
        auditLogService.record(new AuditLogInput(10L, 400L, fxType, "02", "delete",
                Map.of("k", 7), Map.of("k", 8)));
    }

    private List<String> objectIdsOf(PageResult<AuditLog> page) {
        return page.list().stream().map(AuditLog::objectId).toList();
    }

    private PageResult<AuditLog> querySorted(String sortBy, String sortDir) {
        return auditLogService.query(null, null, fxType, null, null, 1, 20, sortBy, sortDir);
    }

    /** No sort key keeps the historical id DESC order; the legacy 7-argument
     * call delegates to the same default (zero drift for existing callers). */
    @Test
    void querySortAbsent_keepsHistoricalIdDesc() {
        seedAuditSortFixture();
        List<String> expected = List.of("02", "30", "05", "10");
        assertEquals(expected, objectIdsOf(querySorted(null, null)));
        assertEquals(expected, objectIdsOf(querySorted("", "")));
        assertEquals(expected, objectIdsOf(
                auditLogService.query(null, null, fxType, null, null, 1, 20)));
    }

    /** object_id asc/desc mirror each other over the whole window (text order,
     * not numeric — "02" < "05" < "10" < "30"). */
    @Test
    void querySortObjectId_bothDirections() {
        seedAuditSortFixture();
        assertEquals(List.of("02", "05", "10", "30"),
                objectIdsOf(querySorted("object_id", "asc")));
        assertEquals(List.of("30", "10", "05", "02"),
                objectIdsOf(querySorted("object_id", "desc")));
    }

    /** action and operator_id walk their own columns (neither is id order). */
    @Test
    void querySortActionAndOperator_orderByOwnColumns() {
        seedAuditSortFixture();
        assertEquals(List.of("05", "02", "10", "30"),
                objectIdsOf(querySorted("action", "asc")));
        assertEquals(List.of("10", "05", "30", "02"),
                objectIdsOf(querySorted("operator_id", "asc")));
        assertEquals(List.of("02", "30", "05", "10"),
                objectIdsOf(querySorted("operator_id", "desc")));
    }

    /** Ledger form: all four rows share one run-scoped object_type, so the
     * object_type ordering degenerates to a full tie that the constant id
     * DESC fallback must resolve — paging stays deterministic under ties
     * regardless of direction. */
    @Test
    void querySortObjectType_tiePinnedByIdDesc() {
        seedAuditSortFixture();
        assertEquals(List.of("02", "30", "05", "10"),
                objectIdsOf(querySorted("object_type", "asc")));
    }

    /** created_at asc mirrors the insertion order (monotonic timestamps under
     * the append-only trigger); id asc is available and is the exact mirror of
     * the default order. */
    @Test
    void querySortCreatedAtAndIdAsc_useOwnPaths() {
        seedAuditSortFixture();
        assertEquals(List.of("10", "05", "30", "02"),
                objectIdsOf(querySorted("created_at", "asc")));
        assertEquals(List.of("10", "05", "30", "02"),
                objectIdsOf(querySorted("id", "asc")));
    }

    /** Sorting keeps paging consistent: page 2 continues the order, total truthful. */
    @Test
    void querySortAppliesAcrossPages() {
        seedAuditSortFixture();
        var first = auditLogService.query(null, null, fxType, null, null, 1, 2, "object_id", "asc");
        var second = auditLogService.query(null, null, fxType, null, null, 2, 2, "object_id", "asc");
        assertEquals(List.of("02", "05"), objectIdsOf(first));
        assertEquals(List.of("10", "30"), objectIdsOf(second));
        assertEquals(4L, first.total());
    }

    /** An injection-shaped or off-list key is refused fail-closed with a human
     * 400 naming the whitelist; the rows stay intact afterwards. */
    @Test
    void querySortOffListKey_refusedWithWhitelistHint() {
        seedAuditSortFixture();
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> querySorted("object_id; DROP TABLE tp_audit_log", "asc"));
        assertEquals(40001, rejected.getCode());
        assertTrue(rejected.getMessage().contains("created_at"),
                "报错应点名可选列，实际=" + rejected.getMessage());
        assertEquals(4, objectIdsOf(querySorted(null, null)).size(), "行数据不受影响");
    }
}

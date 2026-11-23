package com.sixbean.testpike.core.audit.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.AuditLog;
import com.sixbean.testpike.core.common.clientobject.CursorPage;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Shared dev database discipline : every read is scoped to a
 * fixture-private project segment, so the suite never needs blanket TRUNCATE and
 * concurrent suites writing to tp_audit_log cannot perturb its assertions.
 * Teardown sweeps the segment through the single-source SharedDbFixtureSweep
 * channel (it opens the append-only gate for its own project ids only).
 */
@SpringBootTest(classes = CoreApplication.class)
class AuditLogCursorTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture-private project id: tp_audit_log.project_id has no FK, so rows
 * can be written under a unique segment id without seeding tp_project. */
    private long sliceProjectId;

    @BeforeEach
    void setUp() {
        sliceProjectId = System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        SharedDbFixtureSweep.byProject(jdbcTemplate, sliceProjectId);
    }

    private void record(int n) {
        for (int i = 1; i <= n; i++) {
            auditLogService.record(new AuditLogInput(1L, sliceProjectId, "case",
                    String.valueOf(i), "review",
                    Map.of("status", "待评审"), Map.of("status", "已通过", "seq", i)));
        }
    }

    /** Cursor page within this fixture's project segment (never the whole table). */
    private CursorPage<AuditLog> slicePage(Long afterId, int size) {
        return auditLogService.queryCursor(null, sliceProjectId, null, null, null, afterId, size);
    }

    @Test
    void queryCursor_首页倒序_hasMore与nextCursor正确() {
        record(5);
        CursorPage<AuditLog> first = slicePage(null, 2);
        assertEquals(2, first.list().size(), "首页 size=2 必须返回 2 条");
        assertTrue(first.hasMore(), "还有第 3 条起未取，hasMore 必须为 true");
        List<AuditLog> rows = first.list();
        assertTrue(rows.get(0).id() > rows.get(1).id(), "首页两行必须 id 倒序");
        assertEquals(rows.get(1).id(), first.nextCursor(), "nextCursor 必须为本页末行 id");
    }

    @Test
    void queryCursor_续页游标不重不漏() {
        record(5);
        CursorPage<AuditLog> first = slicePage(null, 2);
        CursorPage<AuditLog> second = slicePage(first.nextCursor(), 2);
        List<Long> firstIds = first.list().stream().map(AuditLog::id).toList();
        List<Long> secondIds = second.list().stream().map(AuditLog::id).toList();
        assertEquals(2, secondIds.size(), "第二页必须取满 2 行");
        assertTrue(firstIds.stream().noneMatch(secondIds::contains), "续页与首页必须零重叠");
        assertTrue(secondIds.get(0) < firstIds.get(firstIds.size() - 1),
                "续页首行 id 必须小于游标（keyset 单调下移）");
        assertTrue(second.hasMore(), "还剩 1 条，hasMore 必须为 true");
    }

    @Test
    void queryCursor_末页hasMore_false且nextCursor_null() {
        record(5);
        CursorPage<AuditLog> p1 = slicePage(null, 2);
        CursorPage<AuditLog> p2 = slicePage(p1.nextCursor(), 2);
        CursorPage<AuditLog> p3 = slicePage(p2.nextCursor(), 2);
        assertEquals(1, p3.list().size(), "末页余 1 条");
        assertFalse(p3.hasMore(), "取尽后 hasMore 必须为 false");
        assertNull(p3.nextCursor(), "取尽后 nextCursor 必须为 null（终止信号）");
    }

    @Test
    void queryCursor_筛选条件与游标叠加生效() {
        record(3);
        auditLogService.record(new AuditLogInput(2L, sliceProjectId, "project", "77", "create",
                null, Map.of("name", "demo")));
        record(2);
        CursorPage<AuditLog> page = auditLogService.queryCursor(
                null, sliceProjectId, "case", null, "review", null, 2);
        assertEquals(2, page.list().size());
        CursorPage<AuditLog> next = auditLogService.queryCursor(
                null, sliceProjectId, "case", null, "review", page.nextCursor(), 4);
        List<String> objectIds = next.list().stream().map(AuditLog::objectId).toList();
        assertEquals(List.of("3", "2", "1"), objectIds,
                "游标续页必须跳过 project 行且与首页无重叠无遗漏");
        assertFalse(next.hasMore(), "case/review 5 条已取尽");
    }

    @Test
    void queryCursor_size超200拒40001人话() {
        record(1);
        ValidationException e = assertThrows(ValidationException.class,
                () -> slicePage(null, 201),
                "游标链路同守 size≤200 纪律（TC-K13-02 不破坏）");
        assertTrue(e.getMessage().contains("单页最大 200"),
                "人话消息须含「单页最大 200」，实际=" + e.getMessage());
    }

    @Test
    void queryCursor_空表返回空页不报错() {
        // Empty-slice equivalent: a never-used project id yields zero rows on a
        // shared database where the physical table always has other rows.
        CursorPage<AuditLog> page = auditLogService.queryCursor(
                null, System.nanoTime(), null, null, null, null, 20);
        assertTrue(page.list().isEmpty(), "空切片必须返回空列表");
        assertFalse(page.hasMore(), "空切片 hasMore=false");
        assertNull(page.nextCursor(), "空切片 nextCursor=null");
    }
}

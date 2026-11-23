package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PageQueryPolicyTest {


    @Test
    void enforceSize_正常区间原样放行() {
        assertEquals(20, PageQueryPolicy.enforceSize(20));
        assertEquals(200, PageQueryPolicy.enforceSize(200), "上限 200 边界放行");
    }

    @Test
    void enforceSize_下限1() {
        assertEquals(1, PageQueryPolicy.enforceSize(0), "IT-BND-09：size=0 → 1");
        assertEquals(1, PageQueryPolicy.enforceSize(-5), "负数 → 1");
    }

    @Test
    void enforceSize_超200拒40001人话() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> PageQueryPolicy.enforceSize(201), "TC-K13-02：size=201 必须拒绝而非钳制");
        assertTrue(e.getMessage().contains("单页最大 200"),
                "人话消息须含「单页最大 200」，实际=" + e.getMessage());
        assertThrows(ValidationException.class,
                () -> PageQueryPolicy.enforceSize(100_000), "更大超限同样拒绝");
    }


    @Test
    void clampSize_虚拟滚动白名单_正常区间原样返回() {
        assertEquals(20, PageQueryPolicy.clampSize(VirtualScrollEndpoint.CASES_LIST, 20));
        assertEquals(1000, PageQueryPolicy.clampSize(VirtualScrollEndpoint.CASES_LIST, 1000),
                "虚拟滚动上限 1000 可达（IT-O4-01）");
        assertEquals(1000, PageQueryPolicy.clampSize(VirtualScrollEndpoint.EXEC_TASKS_LIST, 1000));
    }

    @Test
    void clampSize_虚拟滚动白名单_下限1() {
        assertEquals(1, PageQueryPolicy.clampSize(VirtualScrollEndpoint.CASES_LIST, 0),
                "IT-BND-09：size=0 → 1");
        assertEquals(1, PageQueryPolicy.clampSize(VirtualScrollEndpoint.EXEC_TASKS_LIST, -5),
                "负数 → 1");
    }

    @Test
    void clampSize_虚拟滚动白名单_超限静默钳1000() {
        assertEquals(1000, PageQueryPolicy.clampSize(VirtualScrollEndpoint.CASES_LIST, 1001));
        assertEquals(1000, PageQueryPolicy.clampSize(VirtualScrollEndpoint.EXEC_TASKS_LIST, 100_000),
                "IT-O4-01：白名单端点超限钳制不报错（虚拟滚动一次取数语义保留）");
    }


    @Test
    void clampOffset_页码换算与深分页保护() {
        assertEquals(0, PageQueryPolicy.clampOffset(1, 20, 100_000));
        assertEquals(20, PageQueryPolicy.clampOffset(2, 20, 100_000));
        assertEquals(0, PageQueryPolicy.clampOffset(0, 20, 100_000), "非法页码下限 0");
        assertEquals(100_000, PageQueryPolicy.clampOffset(10_000, 20, 100_000),
                "IT-CASE-07：超大 offset 钳制");
        assertEquals(100_000, PageQueryPolicy.clampOffset(999_999_999, 1000, 100_000),
                "乘法溢出风险：long 计算后再钳");
    }
}

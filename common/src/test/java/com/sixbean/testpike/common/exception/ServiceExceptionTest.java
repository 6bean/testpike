package com.sixbean.testpike.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceExceptionTest {

    @Test
    void 单参构造器_message原样_code为空() {
        ServiceException ex = new ServiceException("业务处理失败");
        assertEquals("业务处理失败", ex.getMessage());
        assertNull(ex.getCode(), "单参构造器 code 必须为 null（handler 兜底 50000）");
    }

    @Test
    void 双参构造器_message与code() {
        ServiceException ex = new ServiceException("用例不存在或已被删除", BizErrorCode.NOT_FOUND);
        assertEquals("用例不存在或已被删除", ex.getMessage());
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void 三参构造器_变长args_占位符格式化修复() {
        ServiceException ex = new ServiceException("用例编号 %s 已存在（并发冲突），请重试",
                BizErrorCode.RESOURCE_CONFLICT, "TPC-2026-001");
        assertEquals("用例编号 TPC-2026-001 已存在（并发冲突），请重试", ex.getMessage(),
                "%s 必须被 args 实际值替换");
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertArrayEquals(new Object[]{"TPC-2026-001"}, ex.getArgs(), "args 必须保留（handler 兜底格式化）");
    }

    @Test
    void 多占位符_按序替换() {
        ServiceException ex = new ServiceException("用户 %s 不在项目 %s 中",
                BizErrorCode.VALIDATION_ERROR, "zhangsan", "P001");
        assertEquals("用户 zhangsan 不在项目 P001 中", ex.getMessage());
    }

    @Test
    void 无占位符带args_原样返回() {
        ServiceException ex = new ServiceException("固定文案", BizErrorCode.FORBIDDEN, "extra");
        assertEquals("固定文案", ex.getMessage());
    }

    @Test
    void int字面量code_自动装箱兼容() {
        ServiceException ex = new ServiceException("评审失败: 用例 %s 不存在", 40401, 100L);
        assertEquals(40401, ex.getCode());
        assertEquals("评审失败: 用例 100 不存在", ex.getMessage());
    }
}

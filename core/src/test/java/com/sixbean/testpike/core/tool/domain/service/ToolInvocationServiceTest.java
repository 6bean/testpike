package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationQuery;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ToolInvocationServiceTest {

    @Autowired
    private ToolInvocationService toolInvocationService;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ToolSuiteSupport support;

    @BeforeEach
    void prepareFixtureSuite() {
        support = new ToolSuiteSupport(jdbcTemplate, List.of("tp_tool_invocation"));
        support.begin();
    }

    @AfterEach
    void sweepToolFixture() {
        support.sweep();
    }

    // ---- helpers ----

    private long createPublishedTool(String name) {
        // R37: fixture-scoped rows instead of a blanket TRUNCATE (the shipped
        // 48-row tp_tool baseline stays intact; cleanup walks the FK graph).
        return support.createPublishedTool(toolService, 1L,
                new ToolCreateInput(null, name, 1,
                        "取数", null, "正文内容", null, null, null, null, null, null, null)).id();
    }

    
    private void insertInvocation(long toolId, String caller, int success, Long durationMs,
                                  String error, String ageExpr) {
        jdbcTemplate.update("INSERT INTO tp_tool_invocation (caller, tool_id, tool_name, "
                        + "success, duration_ms, error, created_at) "
                        + "VALUES (?, ?, '取数助手', ?, ?, ?, now() - " + ageExpr + ")",
                caller, toolId, success, durationMs, error);
    }

    private static ToolInvocationQuery query(long toolId, int page, int size,
                                             String start, String end) {
        return new ToolInvocationQuery(page, size, toolId, start, end);
    }


    @Test
    void 列表按工具圈定_倒序分页_成败耗时错误回显() {
        long toolId = createPublishedTool("取数助手");
        long otherTool = createPublishedTool("另一个工具");
        insertInvocation(toolId, "user", 1, 1200L, null, "interval '30 minutes'");
        insertInvocation(toolId, "ai", 0, 45000L, "调用超时", "interval '2 hours'");
        insertInvocation(toolId, "mcp", 1, null, null, "interval '3 hours'");
        insertInvocation(otherTool, "user", 1, 100L, null, "interval '1 minutes'");

        PageResult<ToolInvocationInfo> page1 =
                toolInvocationService.list(query(toolId, 1, 2, null, null));
        assertEquals(3L, page1.total());
        assertEquals(2, page1.list().size());
        ToolInvocationInfo newest = page1.list().get(0);
        assertEquals("user", newest.caller());
        assertEquals(1, newest.success());
        assertEquals(1200L, newest.durationMs());
        ToolInvocationInfo second = page1.list().get(1);
        assertEquals("ai", second.caller());
        assertEquals(0, second.success());
        assertEquals("调用超时", second.error());
        PageResult<ToolInvocationInfo> page2 =
                toolInvocationService.list(query(toolId, 2, 2, null, null));
        assertEquals(1, page2.list().size());
        assertEquals("mcp", page2.list().get(0).caller());
        assertTrue(page2.list().get(0).durationMs() == null);
    }

    @Test
    void 时间范围过滤命中窗口内行() {
        long toolId = createPublishedTool("取数助手");
        insertInvocation(toolId, "user", 1, 10L, null, "interval '10 minutes'");
        insertInvocation(toolId, "user", 1, 10L, null, "interval '5 days'");
        String start = LocalDateTime.now().minusDays(1).withNano(0).toString();

        PageResult<ToolInvocationInfo> page =
                toolInvocationService.list(query(toolId, 1, 20, start, null));
        assertEquals(1L, page.total(), "5 天前的留痕必须被 start 窗口裁掉");
    }


    @Test
    void 时间倒置_40001人话() {
        long toolId = createPublishedTool("取数助手");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> toolInvocationService.list(
                        query(toolId, 1, 20, now.plusDays(1).toString(), now.toString())));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("不能晚于"), () -> "人话须点出倒置: " + ex.getMessage());
    }

    @Test
    void 跨度超90天_40001人话() {
        long toolId = createPublishedTool("取数助手");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> toolInvocationService.list(
                        query(toolId, 1, 20, now.minusDays(91).toString(), now.toString())));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("90"), () -> "人话须点出上限: " + ex.getMessage());
    }

    @Test
    void 跨度恰90天_放行() {
        long toolId = createPublishedTool("取数助手");
        insertInvocation(toolId, "user", 1, 10L, null, "interval '10 minutes'");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        PageResult<ToolInvocationInfo> page = toolInvocationService.list(
                query(toolId, 1, 20, now.minusDays(90).toString(), now.toString()));
        assertEquals(1L, page.total());
    }

    @Test
    void 非法时间格式_40001人话() {
        long toolId = createPublishedTool("取数助手");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> toolInvocationService.list(
                        query(toolId, 1, 20, "2026/09/10 12:00", null)));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("格式"), () -> "人话须点出格式: " + ex.getMessage());
    }


    @Test
    void 工具不存在_40401() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> toolInvocationService.list(query(999_999L, 1, 20, null, null)));
        assertEquals(40401, ex.getCode());
    }
}

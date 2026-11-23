package com.sixbean.testpike.api.mcp.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpToolCatalogTest {

    private final McpToolCatalog catalog = new McpToolCatalog();

    @Test
    void 工具二十二件齐备名称精确() {
        Set<String> names = catalog.all().stream()
                .map(McpToolDefinition::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("create_case", "delete_case", "tag_case",
                "list_requirements", "get_cases", "get_script", "get_exec_result",
                "list_mock_spaces", "inspect_mock_hits",
                "create_mock_stub", "publish_mock_space",
                "doc.upload", "doc_task.poll", "doc_task.cancel",
                "gen.cases_from_requirement", "script.writeback", "mcp.review.confirm",
                "exec.run", "exec.status", "exec.cancel", "report.fetch",
                "tokens.usage"), names);
        assertEquals(22, catalog.all().size());
    }

    @Test
    void 令牌用量读件形制要素齐备() {
        McpToolDefinition usage = catalog.byName("tokens.usage").orElseThrow();
        assertFalse(usage.writeTool(), "用量观测是只读件，不带写语义");
        assertEquals("mcp.read", usage.requiredScope(), "读件随 mcp.read，无需新 scope");
        String d = usage.description();
        assertTrue(d.contains("本次调用所出示"), "账目属于本次出示的 Token: " + d);
        assertTrue(d.contains("调用次数"), "观测形态一: 调用次数: " + d);
        assertTrue(d.contains("失败次数"), "观测形态二: 失败次数: " + d);
        assertTrue(d.contains("p95"), "观测形态三: p95 延迟: " + d);
        assertTrue(usage.inputSchemaJson().contains("\"type\":\"object\""), "inputSchema object 根");
        assertTrue(!usage.inputSchemaJson().contains("project_id"), "令牌自持面无项目参数: " + usage.inputSchemaJson());
    }

    @Test
    void 会话内确认闭环两枚描述含形制要素() {
        String writeback = catalog.byName("script.writeback").orElseThrow().description();
        assertTrue(writeback.contains("确认码"), "回执必须对外声明一次性确认码: " + writeback);
        assertTrue(writeback.contains("mcp.review.confirm"), "回执点名会话内确认工具: " + writeback);
        assertTrue(writeback.contains("无需登录"), "禁要求登录平台界面完成确认: " + writeback);
        assertTrue(writeback.contains("Idempotency-Key"), "写型幂等键要素: " + writeback);
        assertTrue(writeback.contains("mcp.script"), "scope 声明要素: " + writeback);
        assertTrue(writeback.contains("待评审"), "落地语义钉待评审（评审门不破）: " + writeback);
        String confirm = catalog.byName("mcp.review.confirm").orElseThrow().description();
        assertTrue(confirm.contains("一次性"), "确认码一次性语义上屏: " + confirm);
        assertTrue(confirm.contains("accept") && confirm.contains("reject"),
                "决定值域必须双语枚举: " + confirm);
        assertTrue(confirm.contains("mcp.review"), "scope 声明要素: " + confirm);
        assertTrue(confirm.contains("无需登录"), "禁要求登录平台界面完成确认: " + confirm);
    }

    /**
     * Execution-channel contract (four tools): the outward description is the
     * contract an external agent reads, so it has to state the shared trigger
     * (no second execution path), the write obligations, the follow-up tools it
     * answers with, and the pass-rate/no-recomputation scope of the summary.
     */
    @Test
    void 执行面四件契约钉并案触发与会话内后续() {
        String run = catalog.byName("exec.run").orElseThrow().description();
        assertTrue(run.contains("mcp.exec"), "scope 声明要素: " + run);
        assertTrue(run.contains("Idempotency-Key"), "写型幂等键要素: " + run);
        assertTrue(run.contains("exec.status") && run.contains("report.fetch"),
                "非阻塞回执须点名轮询件与结果件: " + run);
        assertTrue(run.contains("dry_run"), "预检半拍要素: " + run);
        assertTrue(run.contains("同一处理件"), "并案口径上屏（不另开执行路径）: " + run);
        assertTrue(run.contains("不阻塞"), "触发回执承诺不等待终态: " + run);

        String cancel = catalog.byName("exec.cancel").orElseThrow().description();
        assertTrue(cancel.contains("Idempotency-Key"), "写型幂等键要素: " + cancel);
        assertTrue(cancel.contains("终态") && cancel.contains("未产生任何变更"),
                "终态取消=零变更语义上屏: " + cancel);
        assertTrue(cancel.contains("同一处理件"), "取消同样并案: " + cancel);

        String status = catalog.byName("exec.status").orElseThrow().description();
        assertTrue(status.contains("case_count") && status.contains("status_label"),
                "进度出参点名到字段: " + status);
        assertFalse(status.contains("Idempotency-Key"), "只读件不承诺写型幂等键: " + status);

        String report = catalog.byName("report.fetch").orElseThrow().description();
        assertTrue(report.contains("通过率") && report.contains("不重算"),
                "比率同源口径上屏: " + report);
        assertTrue(report.contains("不含逐条用例明细"), "摘要不倾倒明细: " + report);
        assertEquals("mcp.exec", McpToolCatalog.SCOPE_EXEC);
    }

    @Test
    void 写读标记与scope_三mcp_case两mcp_mock七mcp_read三mcp_doc一mcp_gen一mcp_script一mcp_review() {
        for (McpToolDefinition tool : catalog.all()) {
            switch (tool.name()) {
                case "create_case", "delete_case", "tag_case" -> {
                    assertTrue(tool.writeTool(), "写工具标记缺失: " + tool.name());
                    assertEquals("mcp.case", tool.requiredScope());
                }
                case "create_mock_stub", "publish_mock_space" -> {
                    assertTrue(tool.writeTool(), "写工具标记缺失: " + tool.name());
                    assertEquals("mcp.mock", tool.requiredScope());
                }
                case "list_requirements", "get_cases", "get_script", "get_exec_result",
                        "list_mock_spaces", "inspect_mock_hits", "tokens.usage" -> {
                    assertFalse(tool.writeTool(), "只读工具误标写: " + tool.name());
                    assertEquals("mcp.read", tool.requiredScope());
                }
                case "doc.upload", "doc_task.cancel" -> {
                    assertTrue(tool.writeTool(), "写工具标记缺失: " + tool.name());
                    assertEquals("mcp.doc", tool.requiredScope());
                }
                case "doc_task.poll" -> {
                    assertFalse(tool.writeTool(), "只读工具误标写: " + tool.name());
                    assertEquals("mcp.doc", tool.requiredScope());
                }
                case "gen.cases_from_requirement" -> {
                    assertTrue(tool.writeTool(), "写工具标记缺失: " + tool.name());
                    assertEquals("mcp.gen", tool.requiredScope());
                }
                case "script.writeback" -> {
                    assertTrue(tool.writeTool(), "写工具标记缺失: " + tool.name());
                    assertEquals("mcp.script", tool.requiredScope());
                }
                case "mcp.review.confirm" -> {
                    assertTrue(tool.writeTool(), "写工具标记缺失: " + tool.name());
                    assertEquals("mcp.review", tool.requiredScope());
                }
                case "exec.run", "exec.cancel" -> {
                    assertTrue(tool.writeTool(), "写工具标记缺失: " + tool.name());
                    assertEquals("mcp.exec", tool.requiredScope());
                }
                case "exec.status", "report.fetch" -> {
                    assertFalse(tool.writeTool(), "只读工具误标写: " + tool.name());
                    assertEquals("mcp.exec", tool.requiredScope());
                }
                default -> throw new AssertionError("未知工具: " + tool.name());
            }
        }
    }

    @Test
    void inputSchema合法JSON且type_object含required声明() {
        for (McpToolDefinition tool : catalog.all()) {
            assertNotNull(tool.inputSchemaJson(), "inputSchema 缺失: " + tool.name());
            assertTrue(tool.inputSchemaJson().contains("\"type\":\"object\"")
                            || tool.inputSchemaJson().contains("\"type\": \"object\""),
                    "inputSchema 非 object 根: " + tool.name() + " = " + tool.inputSchemaJson());
            assertFalse(tool.description().isBlank(), "description 缺失: " + tool.name());
        }
    }

    @Test
    void delete_case与create_case文案钉审批与待评审语义() {
        assertTrue(catalog.byName("delete_case").orElseThrow().description().contains("审批"),
                "delete_case 必须对外声明走审批单（PRD 禁立即删）");
        assertTrue(catalog.byName("create_case").orElseThrow().description().contains("待评审"),
                "create_case 必须对外声明落待评审（评审质量门不破）");
    }

    @Test
    void 文档通道契约面_回执句柄与非阻塞语义在文案与schema双在位() {
        String upload = catalog.byName("doc.upload").orElseThrow().description();
        assertTrue(upload.contains("Idempotency-Key"),
                "写工具必须对外声明幂等键要求: doc.upload");
        assertTrue(upload.contains("task_id") && upload.contains("不阻塞"),
                "doc.upload 必须声明提交即回执句柄、长链不阻塞: " + upload);
        assertTrue(upload.contains("mcp.doc.max-bytes"),
                "大小上限必须点名配置键名与默认值（超限态指路口径）: " + upload);
        String uploadSchema = catalog.byName("doc.upload").orElseThrow().inputSchemaJson();
        assertTrue(uploadSchema.contains("\"required\":[\"project_id\",\"name\",\"content_base64\"]"),
                "doc.upload 必填三元组: " + uploadSchema);
        String pollSchema = catalog.byName("doc_task.poll").orElseThrow().inputSchemaJson();
        assertTrue(pollSchema.contains("\"required\":[\"task_id\"]"),
                "doc_task.poll 必填 task_id: " + pollSchema);
        String cancelSchema = catalog.byName("doc_task.cancel").orElseThrow().inputSchemaJson();
        assertTrue(cancelSchema.contains("\"required\":[\"task_id\"]"),
                "doc_task.cancel 必填 task_id: " + cancelSchema);
        String poll = catalog.byName("doc_task.poll").orElseThrow().description();
        assertTrue(poll.contains("state") && poll.contains("requirement_count")
                        && poll.contains("fidelity"),
                "doc_task.poll 必须声明出参四形态: " + poll);
    }

    @Test
    void 目录列表按名称稳定排序() {
        List<String> ordered = catalog.all().stream().map(McpToolDefinition::name).toList();
        assertEquals(ordered.stream().sorted().toList(), ordered);
    }
}

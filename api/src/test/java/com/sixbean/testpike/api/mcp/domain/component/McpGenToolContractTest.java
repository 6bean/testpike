package com.sixbean.testpike.api.mcp.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract assertions for the MCP generation trigger catalog entry
 * (gen.cases_from_requirement): write marking, scope, receipt handle,
 * governance-key naming, mode/dry_run surface and the human review queue
 * semantics. Lives beside McpToolCatalogTest's shape so the doc-channel
 * and gen-channel contract tests stay independent files.
 */
class McpGenToolContractTest {

    private final McpToolCatalog catalog = new McpToolCatalog();

    @Test
    void 生成触发契约面_回执句柄上限键模式与待评审语义在位() {
        McpToolDefinition gen = catalog.byName("gen.cases_from_requirement").orElseThrow();
        assertNotNull(gen.inputSchemaJson(), "inputSchema 缺失");
        assertTrue(gen.writeTool(), "生成触发是写型工具");

        String d = gen.description();
        assertTrue(d.contains("Idempotency-Key"), "写工具必须对外声明幂等键要求: " + d);
        assertTrue(d.contains("mcp.gen.max-requirements"), "条数上限必须点名治理键: " + d);
        assertTrue(d.contains("get_cases"), "回执句柄必须点名结果查询伙伴: " + d);
        assertTrue(d.contains("待评审"), "生成结果必须对外声明落待评审（评审质量门不破）: " + d);
        assertTrue(d.contains("task_id") && d.contains("不阻塞"), "提交即回执句柄、长链不阻塞: " + d);

        String s = gen.inputSchemaJson();
        assertTrue(s.contains("\"required\":[\"project_id\",\"requirement_ids\"]"),
                "必填二元组: " + s);
        assertTrue(s.contains("dry_run"), "schema 必须暴露 dry_run: " + s);
        assertTrue(s.contains("mode"), "schema 必须暴露 mode: " + s);
    }
}

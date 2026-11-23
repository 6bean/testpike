package com.sixbean.testpike.api.mcp.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpToolVisibilityFilterTest {

    private final McpToolCatalog catalog = new McpToolCatalog();
    private final McpToolVisibilityFilter filter = new McpToolVisibilityFilter(catalog);

    private Set<String> names(List<String> scopes, boolean readonly) {
        return filter.visibleFor(scopes, readonly).stream()
                .map(McpToolDefinition::name)
                .collect(Collectors.toSet());
    }

    @Test
    void 三scope齐全_非只读_十二件全可见() {
        assertEquals(12, names(List.of("mcp.case", "mcp.mock", "mcp.read"), false).size());
    }

    @Test
    void 仅mcp_read_scope_全部写件从列表消失含mock写件() {
        Set<String> visible = names(List.of("mcp.read"), false);
        assertEquals(Set.of("list_requirements", "get_cases", "get_script", "get_exec_result",
                "list_mock_spaces", "inspect_mock_hits", "tokens.usage"), visible);
    }

    @Test
    void 仅mcp_case_scope_读件与mock族从列表消失() {
        Set<String> visible = names(List.of("mcp.case"), false);
        assertEquals(Set.of("create_case", "delete_case", "tag_case"), visible);
    }

    @Test
    void 仅mcp_mock_scope_两mock写件可见() {
        Set<String> visible = names(List.of("mcp.mock"), false);
        assertEquals(Set.of("create_mock_stub", "publish_mock_space"), visible);
    }

    @Test
    void 只读开关true_写工具从列表消失非仅拒绝() {
        Set<String> visible = names(List.of("mcp.case", "mcp.mock", "mcp.read"), true);
        assertEquals(Set.of("list_requirements", "get_cases", "get_script", "get_exec_result",
                "list_mock_spaces", "inspect_mock_hits", "tokens.usage"), visible);
        assertTrue(filter.visibleFor(List.of("mcp.case", "mcp.mock", "mcp.read"), true).stream()
                .noneMatch(McpToolDefinition::writeTool), "只读态列表仍有写工具=违反 TC-MCP-06");
    }

    @Test
    void 无相关scope_列表为空但类型合法() {
        assertTrue(names(List.of("mcp.other"), false).isEmpty());
        assertTrue(names(List.of(), false).isEmpty());
    }

    @Test
    void 多scope并集去重_mock族随scope缺席不泄露() {
        List<String> dup = List.of("mcp.read", "mcp.read", "mcp.case");
        // case 3 + read 7 = 10 visible; the two mcp.mock tools stay hidden
        // because their scope is absent from the token
        assertEquals(10, names(dup, false).size());
    }
}

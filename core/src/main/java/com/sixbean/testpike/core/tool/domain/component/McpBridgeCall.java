package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.tool.api.dto.ToolTestRunResult;

/**
 * Result seam between the MCP_BRIDGE execution branch and the trace/audit
 * single point. Carries the test-run result plus the honest desensitization
 * facet for {@code tp_tool_invocation.masked}:
 * <ul>
 *   <li>{@code 1} — the gate was on and the outbound body was scrubbed before
 *       being handed to the MCP client;</li>
 *   <li>{@code 0} — the gate was explicitly downgraded, the raw body passed
 *       through and the audit says so;</li>
 *   <li>{@code null} — no outbound body ever left (a refusal path such as the
 *       desensitization failure), so the facet does not apply: the column
 *       stays nullable exactly for this "no outbound body" semantics.</li>
 * </ul>
 * The facet is read from the shared gate object that actually prepared the
 * body — call sites re-reading the config key are banned (single source).
 *
 * @param result   the test-run outcome as reported to the caller
 * @param masked   outbound desensitization facet, nullable as above
 */
public record McpBridgeCall(ToolTestRunResult result, Integer masked) {
}

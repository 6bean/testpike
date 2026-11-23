package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.List;

/**
 * Authenticated MCP caller (GA1): the verified token owner plus the project pin
 * resolved once at auth time. One value object is threaded through both gate
 * points of the tool gateway so membership and the pin are decided at the same
 * single point — no second ownership check exists anywhere (single-source rule).
 *
 * <p>An empty pin means unrestricted: every pre-GA1 token keeps the exact old
 * door (core membership only), zero behavior drift. A non-empty pin denies any
 * other target before the first core hop, with a refusal that names the
 * token's current project set and the rejected target.
 *
 * @param userId       authenticated token owner id (RBAC and audit origin)
 * @param projectScope pinned project ids as strings; empty = unrestricted
 */
public record McpCaller(long userId, List<String> projectScope) {

    public McpCaller {
        projectScope = projectScope == null ? List.of()
                : projectScope.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .toList();
    }

    /** Caller of a token without a project pin (the pre-GA1 shape). */
    public static McpCaller unrestricted(long userId) {
        return new McpCaller(userId, List.of());
    }

    /**
     * Deny a target outside the pin before anything executes (zero side effects:
     * every call site sits ahead of the operation's first core hop).
     */
    public void assertWithinProjectPin(long projectId) {
        if (projectScope.isEmpty() || projectScope.contains(String.valueOf(projectId))) {
            return;
        }
        throw new ServiceException(
                "当前 API Token 限定项目 " + String.join("、", projectScope) + "，目标项目 "
                        + projectId + " 不在限定范围内：本次调用已被拒绝，未读取也未写入任何数据。"
                        + "如需访问该项目，请在 API Token 管理页调整该令牌的限定项目。",
                BizErrorCode.FORBIDDEN);
    }
}

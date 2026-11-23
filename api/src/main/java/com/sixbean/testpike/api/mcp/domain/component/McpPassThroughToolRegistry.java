package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.McpPassThroughRemoteTool;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpToolGateway;
import com.sixbean.testpike.api.mcp.infrastructure.remote.RestCoreConfigClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pass-through tool family on the external MCP face: names and schemas that come from core's
 * interface catalog instead of from a list kept in this module.
 *
 * <p>Why a snapshot rather than a call per request: tools/list is polled by agents, and the catalog
 * behind it only changes when the application is redeployed. A refresh window (seconds, config
 * {@code mcp.api-access.refresh-seconds}) therefore costs nothing in correctness and keeps an agent
 * listing from turning into a call storm against core.
 *
 * <p>Why the previous snapshot is kept on failure: if core is briefly unreachable and the face
 * silently emptied, every integration that legitimately holds those tools would appear broken to its
 * owner. Serving the last known set (with the log line) is the honest degraded state — calls still
 * go through core, so nothing is answered from a stale copy of data.
 */
@Component
public class McpPassThroughToolRegistry {

    /** Every pass-through tool is a read tool, so one scope governs the whole family. */
    public static final String REQUIRED_SCOPE = McpToolCatalog.SCOPE_READ;

    /** Name prefix kept in the projected names by core; used only for routing decisions. */
    private static final String NAME_PREFIX = "api_";

    private static final Logger log = LoggerFactory.getLogger(McpPassThroughToolRegistry.class);

    /** Refresh window of the tool face, read hot from the platform's own governance key. */
    public static final String KEY_REFRESH_SECONDS = "mcp.api-access.refresh-seconds";
    private static final long DEFAULT_REFRESH_SECONDS = 300L;

    private final McpToolGateway gateway;
    private final java.util.function.LongSupplier refreshSeconds;

    private volatile Map<String, PassThroughTool> snapshot = Map.of();
    private volatile long lastAttemptAt;

    public McpPassThroughToolRegistry(McpToolGateway gateway, RestCoreConfigClient configClient) {
        this.gateway = gateway;
        // The same reader every other MCP limit uses: a second config path here would drift the day
        // the key's spelling or fallback changes.
        this.refreshSeconds = configClient.secondsSupplier(KEY_REFRESH_SECONDS, DEFAULT_REFRESH_SECONDS);
    }

    /** Currently known tools, stably ordered by name (the wire list must not shuffle). */
    public List<PassThroughTool> tools() {
        maybeRefresh();
        return snapshot.values().stream()
                .sorted(java.util.Comparator.comparing(PassThroughTool::name))
                .toList();
    }

    /** Resolve one name to its catalog entry (empty = not a pass-through tool, or unknown). */
    public Optional<PassThroughTool> byName(String name) {
        if (name == null || !name.startsWith(NAME_PREFIX)) {
            return Optional.empty();
        }
        maybeRefresh();
        return Optional.ofNullable(snapshot.get(name));
    }

    private void maybeRefresh() {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1L, refreshSeconds.getAsLong()) * 1000L;
        if (now - lastAttemptAt < windowMillis && !snapshot.isEmpty()) {
            return;
        }
        lastAttemptAt = now;
        try {
            List<McpPassThroughRemoteTool> fetched = gateway.listPassThroughTools();
            Map<String, PassThroughTool> next = new LinkedHashMap<>();
            for (McpPassThroughRemoteTool row : fetched) {
                if (row.name() == null || row.name().isBlank() || row.endpointKey().isBlank()) {
                    continue;
                }
                next.put(row.name(), new PassThroughTool(row.name(),
                        new McpToolDefinition(row.name(), row.description(), false, REQUIRED_SCOPE,
                                row.inputSchemaJson()),
                        row.endpointKey()));
            }
            snapshot = Map.copyOf(next);
        } catch (RuntimeException ex) {
            log.warn("pass-through tool face refresh failed, keeping last known set: {}",
                    ex.getMessage());
        }
    }

    /**
     * One external tool: the MCP definition plus the catalog key its call must be routed to.
     *
     * @param name        tool name on the wire
     * @param definition  definition handed to tools/list (read-only, scope mcp.read)
     * @param endpointKey catalog key handed to the pass-through read door
     */
    public record PassThroughTool(String name, McpToolDefinition definition, String endpointKey) {
    }
}

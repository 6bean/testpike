package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpInvocationRemoteInput;
import com.sixbean.testpike.common.mcp.McpOutboundDesensitizeGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class McpClientPool {

    private static final Logger log = LoggerFactory.getLogger(McpClientPool.class);

 
    public static final String CALL_BUDGET_KEY = "mcp.call-budget";

    /** Outbound desensitization switch key (hot-updatable; factory default on,
     * an explicit "false" is the enterprise pass-through downgrade tier). */
    public static final String OUTBOUND_ENFORCE_KEY = McpOutboundDesensitizeGate.ENFORCE_KEY;
 
    static final int DEFAULT_BUDGET = 8;
    
    static final String[] KNOWLEDGE_KEYWORDS = {"kb", "search", "query", "get", "list", "fetch", "read"};
    
    static final String[] DANGEROUS_KEYWORDS = {"shell", "exec", "drop", "delete", "remove", "write", "create", "update"};

    private final CoreGateway coreGateway;
    private final ToolInvocationRecorder recorder;
    private final McpSessionFactory sessionFactory;

    public McpClientPool(CoreGateway coreGateway, ToolInvocationRecorder recorder,
                         McpSessionFactory sessionFactory) {
        this.coreGateway = coreGateway;
        this.recorder = recorder;
        this.sessionFactory = sessionFactory;
    }

    
    public McpTaskToolBag openTask(Long taskId, long projectId) {
        int budget = resolveBudget();
        List<McpAssemblyRemoteServer> servers;
        try {
            servers = coreGateway.getMcpAssemblyServers(projectId);
        } catch (Exception e) {
            log.warn("MCP 装配取数失败（本任务零工具，不阻断生成）projectId={}: {}",
                    projectId, e.getMessage());
            return new McpTaskToolBag(List.of(), budget, taskId, null, outboundGate());
        }
        List<McpToolSession> ready = new ArrayList<>();
        List<McpInvocationRemoteInput> traces = new ArrayList<>();
        for (McpAssemblyRemoteServer s : servers) {
            if (s.toolNames() == null || s.toolNames().isEmpty()) {
                continue;
            }
            McpToolSession session = sessionFactory.create(s);
            String failure = session.connect();
            if (failure != null) {
                log.warn("MCP 服务握手失败，本任务跳过 server={} name={}: {}",
                        s.serverId(), s.name(), failure);
                traces.add(McpInvocationRemoteInput.fail(taskId, s.serverId(),
                        "(connect)", null, failure, null, null));
                continue;
            }
            ready.add(session);
        }
        return new McpTaskToolBag(ready, budget, taskId, traces, outboundGate());
    }

 
    public void finish(McpTaskToolBag bag) {
        if (bag == null) {
            return;
        }
        recorder.record(bag.invocationTraces());
        if (bag.budgetExceeded()) {
            log.warn("工具调用超预算（mcp.call-budget={}），第 {} 次起的调用已熔断拒绝",
                    bag.callBudget(), bag.usedCount() + 1);
        }
    }

 
    public static boolean isKnowledgeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String lower = toolName.toLowerCase();
        for (String d : DANGEROUS_KEYWORDS) {
            if (lower.contains(d)) {
                return false;
            }
        }
        for (String k : KNOWLEDGE_KEYWORDS) {
            if (lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private int resolveBudget() {
        return SysConfigInts.positiveInt(coreGateway.getSysConfig(CALL_BUDGET_KEY),
                DEFAULT_BUDGET);
    }

    /**
 * outbound desensitization gate for one task (K8 key
 * {@code ai.mcp.outbound-desensitize-enforce}, hot-updatable, default on).
 * Only an explicit "false" downgrades to pass-through; a missing or
 * unreadable value keeps the gate on (fail-closed, silent downgrade banned).
 */
    private McpOutboundDesensitizeGate outboundGate() {
        String value;
        try {
            value = coreGateway.getSysConfig(OUTBOUND_ENFORCE_KEY);
        } catch (Exception e) {
            log.warn("outbound enforce key read failed, keeping gate on: {}", e.getMessage());
            value = null;
        }
        return McpOutboundDesensitizeGate.fromRawValue(value);
    }
}

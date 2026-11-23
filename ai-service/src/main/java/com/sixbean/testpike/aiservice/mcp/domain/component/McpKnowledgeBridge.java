package com.sixbean.testpike.aiservice.mcp.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class McpKnowledgeBridge {

    private static final Logger log = LoggerFactory.getLogger(McpKnowledgeBridge.class);

    
    static final List<String> KNOWLEDGE_KEYWORDS = List.of("kb", "search", "query", "list");

    private final McpClientPool pool;

    public McpKnowledgeBridge(McpClientPool pool) {
        this.pool = pool;
    }

    
    public String knowledgeSegment(Long taskId, long projectId, String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        McpTaskToolBag bag = null;
        try {
            bag = pool.openTask(taskId, projectId);
            return knowledgeSegment(bag, query);
        } catch (Exception e) {
            log.warn("MCP 知识工具消费异常（本任务零注入，不阻断生成）projectId={}: {}",
                    projectId, e.getMessage());
            return "";
        } finally {
            pool.finish(bag);
        }
    }

    
    public String knowledgeSegment(McpTaskToolBag bag, String query) {
        if (query == null || query.isBlank() || bag == null) {
            return "";
        }
        try {
            if (bag.toolNames().isEmpty()) {
                return "";
            }
            bag.callKnowledgeTools(query, KNOWLEDGE_KEYWORDS);
            return bag.segment();
        } catch (Exception e) {
            log.warn("MCP 知识工具消费异常（本任务零注入，不阻断生成）: {}", e.getMessage());
            return "";
        }
    }
}

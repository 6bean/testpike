package com.sixbean.testpike.admin.mcp.infrastructure.remote.client;

import com.sixbean.testpike.admin.mcp.api.dto.McpProbeResultView;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerCreateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerUpdateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerView;
import com.sixbean.testpike.admin.mcp.api.dto.McpToolSelectionRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;


public interface McpServerClient {

    PageView<McpServerView> list(String keyword, Integer enabled, Integer health,
                                 String sortBy, String sortDir, int page, int size);

    McpServerView create(McpServerCreateRequest request);

    McpServerView get(long id);

    McpServerView update(long id, McpServerUpdateRequest request);

    void delete(long id);

    McpServerView toggle(long id, int enabled);

 
    McpServerView updateTools(long id, McpToolSelectionRequest request);

    McpProbeResultView test(long id);
}

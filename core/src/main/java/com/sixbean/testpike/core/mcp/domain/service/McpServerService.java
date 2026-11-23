package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpServerInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpServerQuery;
import com.sixbean.testpike.core.mcp.api.dto.McpServerUpdateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpToolSelectionInput;


public interface McpServerService {

 
    McpServerInfo create(Long operatorId, McpServerCreateInput input);

    
    McpServerInfo get(Long id);

    
    PageResult<McpServerInfo> list(McpServerQuery query);

    
    McpServerInfo update(Long operatorId, Long id, McpServerUpdateInput input);

 
    McpServerInfo toggle(Long operatorId, Long id, int enabled);

    
    McpServerInfo updateTools(Long operatorId, Long id, McpToolSelectionInput input);

 
    void delete(Long operatorId, Long id);
}

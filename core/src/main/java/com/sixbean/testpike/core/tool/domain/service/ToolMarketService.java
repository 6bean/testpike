package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketDownloadView;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketItem;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketQuery;


public interface ToolMarketService {

    
    PageResult<ToolMarketItem> marketList(ToolMarketQuery query);

    
    ToolMarketDownloadView download(Long toolId, String format);

    
    ToolInfo importTool(Long operatorId, Long projectId, String filename, byte[] content);
}

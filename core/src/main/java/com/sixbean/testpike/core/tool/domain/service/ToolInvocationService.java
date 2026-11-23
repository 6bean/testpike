package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationQuery;


public interface ToolInvocationService {

    
    PageResult<ToolInvocationInfo> list(ToolInvocationQuery query);
}

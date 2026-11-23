package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolDownloadFile;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolQuery;
import com.sixbean.testpike.core.tool.api.dto.ToolRollbackInput;
import com.sixbean.testpike.core.tool.api.dto.ToolUpdateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionInfo;


public interface ToolService {

    
    ToolInfo create(Long operatorId, ToolCreateInput input);

    
    ToolInfo get(Long id);

    
    PageResult<ToolInfo> list(ToolQuery query);

    
    ToolInfo update(Long operatorId, Long id, ToolUpdateInput input);

    
    void delete(Long operatorId, Long id, int expectedVersion);

    
    ToolInfo submit(Long operatorId, Long id, int expectedVersion);

    
    ToolInfo approve(Long operatorId, Long id, int expectedVersion);

    
    ToolInfo reject(Long operatorId, Long id, int expectedVersion, String reason);

    
    ToolInfo retire(Long operatorId, Long id, int expectedVersion);

    
    PageResult<ToolVersionInfo> listVersions(Long id, int page, int size);

    
    ToolVersionDetail getVersionSnapshot(Long id, int version);

    
    ToolDetail getDetail(Long id);

    
    ToolDownloadFile download(Long operatorId, Long id, Integer version);

    
    ToolInfo rollback(Long operatorId, Long id, ToolRollbackInput input);
}

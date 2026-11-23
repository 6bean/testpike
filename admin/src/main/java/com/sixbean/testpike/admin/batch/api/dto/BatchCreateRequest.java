package com.sixbean.testpike.admin.batch.api.dto;

import java.util.List;


public record BatchCreateRequest(long projectId, String name, Long envId, List<Long> caseIds,
                                 Integer notifyFeishu, Long poolTagId, String browser,
                                 Integer cleanAppData) {

    
    public BatchCreateRequest(long projectId, String name, Long envId, List<Long> caseIds,
                              Integer notifyFeishu, Long poolTagId, String browser) {
        this(projectId, name, envId, caseIds, notifyFeishu, poolTagId, browser, null);
    }

    
    public BatchCreateRequest(long projectId, String name, Long envId, List<Long> caseIds,
                              Integer notifyFeishu, Long poolTagId) {
        this(projectId, name, envId, caseIds, notifyFeishu, poolTagId, null);
    }

    
    public BatchCreateRequest(long projectId, String name, Long envId, List<Long> caseIds,
                              Integer notifyFeishu) {
        this(projectId, name, envId, caseIds, notifyFeishu, null);
    }

    
    public BatchCreateRequest(long projectId, String name, Long envId, List<Long> caseIds) {
        this(projectId, name, envId, caseIds, null, null);
    }
}

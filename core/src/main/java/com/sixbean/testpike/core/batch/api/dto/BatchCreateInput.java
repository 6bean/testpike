package com.sixbean.testpike.core.batch.api.dto;

import java.util.List;


public record BatchCreateInput(long projectId, String name, Long envId, List<Long> caseIds,
                               Long templateId, Integer notifyFeishu, Long poolTagId, String browser,
                               Integer cleanAppData) {

    
    public BatchCreateInput(long projectId, String name, Long envId, List<Long> caseIds,
                            Long templateId, Integer notifyFeishu, Long poolTagId, String browser) {
        this(projectId, name, envId, caseIds, templateId, notifyFeishu, poolTagId, browser, null);
    }

    
    public BatchCreateInput(long projectId, String name, Long envId, List<Long> caseIds,
                            Long templateId, Integer notifyFeishu, Long poolTagId) {
        this(projectId, name, envId, caseIds, templateId, notifyFeishu, poolTagId, null, null);
    }

    
    public BatchCreateInput(long projectId, String name, Long envId, List<Long> caseIds,
                            Long templateId, Integer notifyFeishu) {
        this(projectId, name, envId, caseIds, templateId, notifyFeishu, null, null);
    }

    
    public BatchCreateInput(long projectId, String name, Long envId, List<Long> caseIds,
                            Long templateId) {
        this(projectId, name, envId, caseIds, templateId, null, null, null);
    }
}

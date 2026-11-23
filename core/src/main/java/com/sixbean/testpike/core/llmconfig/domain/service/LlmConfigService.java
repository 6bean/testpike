package com.sixbean.testpike.core.llmconfig.domain.service;

import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigBrief;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.api.dto.ProjectLlmConfigInfo;

import java.util.List;


public interface LlmConfigService {

 
    List<LlmConfigInfo> list(Integer modelType);

    
    default List<LlmConfigInfo> list() {
        return list(null);
    }

    
    LlmConfigInfo get(long id);

    
    LlmConfigInfo create(Long operatorId, LlmConfigInput input);

    
    LlmConfigInfo update(Long operatorId, long id, LlmConfigInput input);

    
    void delete(long id);

 
    ProjectLlmConfigInfo getProjectConfig(long projectId);

 
    ProjectLlmConfigInfo updateProjectConfig(Long operatorId, long projectId, Long llmConfigId);

 
    LlmConfigBrief resolveProjectBrief(long projectId);

    
    LlmConfigBrief resolveProjectEmbeddingBrief(long projectId);

    
    List<LlmConfigBrief> listEnabledBriefs();

    
    List<LlmConfigBrief> listEnabledBriefs(Integer modelType);
}

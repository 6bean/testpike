package com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client;

import com.sixbean.testpike.admin.llmconfig.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigSaveRequest;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigView;

import java.util.List;


public interface LlmConfigClient {

 
    List<LlmConfigView> list(Integer modelType);

    
    default List<LlmConfigView> list() {
        return list(null);
    }

    LlmConfigView get(long id);

    LlmConfigView create(LlmConfigSaveRequest request);

    LlmConfigView update(long id, LlmConfigSaveRequest request);

    void delete(long id);

    
    ProjectLlmConfigRef getProjectConfig(long projectId);

    
    ProjectLlmConfigRef updateProjectConfig(long projectId, Long llmConfigId);

    
    List<LlmConfigView> listEnabled();

    /**
 * Paged route-axis golden replay ledger : newest-first switch
 * replays; all three filters optional so the page can pull one family, one
 * switch side, or the whole ledger through the same read.
 */
    GoldenDriftPageView routeReplays(String domain, String controlRef, String candidateRef,
                                     int page, int pageSize);
}

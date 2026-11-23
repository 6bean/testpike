package com.sixbean.testpike.admin.visual.infrastructure.remote.client;

import com.sixbean.testpike.admin.visual.api.dto.VisualBaselineView;

import java.util.List;


public interface VisualBaselineClient {

    
    List<VisualBaselineView> listByCase(long projectId, long caseId);

    
    VisualBaselineView getById(long id);

    
    VisualBaselineView confirm(long id);

    
    void reject(long id);

    
    String viewUrl(long id);
}

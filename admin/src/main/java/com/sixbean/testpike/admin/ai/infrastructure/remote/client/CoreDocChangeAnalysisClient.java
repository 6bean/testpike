package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.DocChangeAnalysisView;
import com.sixbean.testpike.admin.ai.api.dto.RevisionDraftPregenView;
import com.sixbean.testpike.admin.project.api.dto.PageView;


public interface CoreDocChangeAnalysisClient {

    
    DocChangeAnalysisView analyze(long newDocumentId);

    
    PageView<DocChangeAnalysisView> list(long projectId, Integer status, int page, int size);

    
    DocChangeAnalysisView get(long id);

    
    DocChangeAnalysisView handle(long id, String note);

    /** Pre-generate revision-draft cards for one pending analysis . */
    RevisionDraftPregenView pregenDrafts(long id);
}

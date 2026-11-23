package com.sixbean.testpike.admin.defects.infrastructure.remote.client;

import com.sixbean.testpike.admin.defects.api.dto.DefectCreateRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectExportResultView;
import com.sixbean.testpike.admin.defects.api.dto.DefectLibraryView;
import com.sixbean.testpike.admin.defects.api.dto.DefectSummaryView;
import com.sixbean.testpike.admin.defects.api.dto.DefectTransitionRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectUpdateRequest;
import com.sixbean.testpike.admin.defects.api.dto.ExportDefectsRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;


public interface CoreDefectLibraryClient {

    
    DefectLibraryView create(DefectCreateRequest request);

    /** Defect list (filters + paging + whitelisted sort pass-through; programId = optional project-set lens). */
    PageView<DefectLibraryView> list(long projectId, Integer status, Integer severity, Long assigneeId,
                                     Long caseId, String keyword, int page, int size,
                                     String sortBy, String sortDir, long programId);

    
    DefectLibraryView get(long id);

    
    DefectLibraryView update(long id, DefectUpdateRequest request);

    
    DefectLibraryView transition(long id, DefectTransitionRequest request);

 
    DefectSummaryView summaryByCase(long caseId);

 
    DefectExportResultView exportDefects(ExportDefectsRequest request);
}

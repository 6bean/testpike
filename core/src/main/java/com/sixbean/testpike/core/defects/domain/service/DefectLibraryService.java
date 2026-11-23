package com.sixbean.testpike.core.defects.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectExportRequest;
import com.sixbean.testpike.core.defects.api.dto.DefectExportResult;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectQuery;
import com.sixbean.testpike.core.defects.api.dto.DefectSummary;
import com.sixbean.testpike.core.defects.api.dto.DefectTransitionInput;
import com.sixbean.testpike.core.defects.api.dto.DefectUpdateInput;


public interface DefectLibraryService {

    
    DefectLibraryInfo create(long operatorId, DefectCreateInput input);

    
    PageResult<DefectLibraryInfo> list(long operatorId, DefectQuery query);

 
    DefectExportResult export(long operatorId, DefectExportRequest request);

    
    DefectLibraryInfo get(long id);

    
    DefectLibraryInfo update(long operatorId, long id, DefectUpdateInput input);

    
    DefectLibraryInfo transition(long operatorId, long id, DefectTransitionInput input);

 
    DefectSummary summaryByCase(long caseId);

 
    int countOpenByPlan(long planId, boolean severityP0Only);
}

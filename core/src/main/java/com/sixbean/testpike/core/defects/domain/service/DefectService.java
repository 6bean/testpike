package com.sixbean.testpike.core.defects.domain.service;

import com.sixbean.testpike.core.defects.api.dto.DefectInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectLinkInput;
import com.sixbean.testpike.core.defects.api.dto.DefectStatusInput;

import java.util.List;


public interface DefectService {

    
    DefectInfo link(long operatorId, DefectLinkInput input);

    
    DefectInfo updateStatus(long operatorId, long id, DefectStatusInput input);

    
    List<DefectInfo> listByCase(long operatorId, long caseId);

    
    DefectInfo get(long id);

    
    long rerunHint(long operatorId, long caseId);
}

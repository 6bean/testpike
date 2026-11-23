package com.sixbean.testpike.admin.defects.infrastructure.remote.client;

import com.sixbean.testpike.admin.defects.api.dto.DefectLinkRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectStatusRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectView;

import java.util.List;


public interface CoreDefectClient {

    
    DefectView link(DefectLinkRequest request);

    
    DefectView updateStatus(long id, DefectStatusRequest request);

    
    DefectView get(long id);

    
    List<DefectView> listByCase(long caseId);

    
    long rerunHint(long caseId);
}

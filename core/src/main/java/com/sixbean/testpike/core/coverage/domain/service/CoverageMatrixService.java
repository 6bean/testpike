package com.sixbean.testpike.core.coverage.domain.service;

import com.sixbean.testpike.core.coverage.api.dto.CoverageMatrixView;


public interface CoverageMatrixService {

    
    CoverageMatrixView matrix(long projectId, String chapter, Integer priority, String endType);
}

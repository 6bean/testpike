package com.sixbean.testpike.admin.coverage.infrastructure.remote.client;

import com.sixbean.testpike.admin.coverage.api.dto.CoverageMatrixView;


public interface CoreCoverageClient {

    
    CoverageMatrixView matrix(long projectId, String chapter, Integer priority, String endType);
}

package com.sixbean.testpike.admin.imports.infrastructure.remote.client;

import com.sixbean.testpike.admin.imports.api.dto.ImportResultView;

import java.util.List;


public interface CoreImportClient {

    
    ImportResultView importCases(long projectId, Integer targetStatus, byte[] file, String fileName);

    
    ImportResultView retryFailedCases(long projectId, Integer targetStatus, List<Integer> retryRows,
                                      byte[] file, String fileName);

    
    ImportResultView importRequirements(long projectId, byte[] file, String fileName);

    
    byte[] caseTemplate();

    
    byte[] requirementTemplate();
}

package com.sixbean.testpike.core.imports.domain.service;

import com.sixbean.testpike.core.imports.api.dto.ImportResult;

import java.util.List;


public interface ImportService {

    
    ImportResult importCases(Long operatorId, long projectId, byte[] file, String fileName, Integer targetStatus);

    
    ImportResult importRequirements(Long operatorId, long projectId, byte[] file, String fileName);

    
    ImportResult retryFailedCases(Long operatorId, long projectId, byte[] file, String fileName,
                                  Integer targetStatus, List<Integer> retryRows);

    
    byte[] caseTemplate();

    
    byte[] requirementTemplate();
}

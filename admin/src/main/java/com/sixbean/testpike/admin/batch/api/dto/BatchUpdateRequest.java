package com.sixbean.testpike.admin.batch.api.dto;

import java.util.List;


public record BatchUpdateRequest(String name, Long envId, List<Long> addCaseIds,
                                 List<Long> removeCaseIds, Integer version, Long poolTagId,
                                 String browser, Integer cleanAppData) {

    
    public BatchUpdateRequest(String name, Long envId, List<Long> addCaseIds,
                              List<Long> removeCaseIds, Integer version, Long poolTagId,
                              String browser) {
        this(name, envId, addCaseIds, removeCaseIds, version, poolTagId, browser, null);
    }

    
    public BatchUpdateRequest(String name, Long envId, List<Long> addCaseIds,
                              List<Long> removeCaseIds, Integer version, Long poolTagId) {
        this(name, envId, addCaseIds, removeCaseIds, version, poolTagId, null);
    }

    
    public BatchUpdateRequest(String name, Long envId, List<Long> addCaseIds,
                              List<Long> removeCaseIds, Integer version) {
        this(name, envId, addCaseIds, removeCaseIds, version, null);
    }

    
    public BatchUpdateRequest(String name, Long envId, List<Long> addCaseIds,
                              List<Long> removeCaseIds) {
        this(name, envId, addCaseIds, removeCaseIds, null, null);
    }
}

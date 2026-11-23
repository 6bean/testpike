package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.StepAliasSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasUpdateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasView;

import java.util.List;


public interface CoreStepAliasClient {

    
    StepAliasView createAlias(StepAliasSaveRequest request);

    
    StepAliasView updateAlias(long id, StepAliasUpdateRequest request);

    
    void deleteAlias(long id);

    
    List<StepAliasView> listAliases(long projectId);

    
    StepAliasView findAlias(long id);
}

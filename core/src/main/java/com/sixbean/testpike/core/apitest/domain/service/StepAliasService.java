package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.StepAliasInfo;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInput;

import java.util.List;
import java.util.Set;


public interface StepAliasService {

    
    StepAliasInfo create(Long operatorId, StepAliasInput input);

    
    StepAliasInfo update(Long operatorId, long aliasId, String kind, String target, String ownerRef);

    
    void delete(Long operatorId, long aliasId);

    
    List<StepAliasInfo> list(long projectId);

    
    StepAliasInfo find(long aliasId);

    
    Set<String> aliasNames(long projectId);
}

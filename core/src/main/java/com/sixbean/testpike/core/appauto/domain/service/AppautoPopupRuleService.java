package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoPopupRuleView;

import java.util.List;


public interface AppautoPopupRuleService {

 
    int STATUS_PENDING = 1;
    int STATUS_CONFIRMED = 2;
    int STATUS_REJECTED = 3;

    
    long learn(long taskId);

    
    List<AppautoPopupRuleView> list(long projectId, Integer status, int limit);

    
    AppautoPopupRuleView find(long ruleId);

    
    AppautoPopupRuleView confirm(Long operatorId, long ruleId);

    
    void reject(Long operatorId, long ruleId);
}

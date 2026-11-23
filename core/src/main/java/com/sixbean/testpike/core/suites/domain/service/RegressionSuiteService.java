package com.sixbean.testpike.core.suites.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.suites.api.dto.SuiteCreateInput;
import com.sixbean.testpike.core.suites.api.dto.SuiteInfo;
import com.sixbean.testpike.core.suites.api.dto.SuiteQuery;
import com.sixbean.testpike.core.suites.api.dto.SuiteRunResult;
import com.sixbean.testpike.core.suites.api.dto.SuiteUpdateInput;


public interface RegressionSuiteService {

    
    SuiteInfo create(long operatorId, SuiteCreateInput input);

    
    SuiteInfo update(long operatorId, long id, SuiteUpdateInput input);

    
    SuiteInfo get(long id);

    
    PageResult<SuiteInfo> list(SuiteQuery query);

    
    void delete(long operatorId, long id);

 
    SuiteRunResult run(long operatorId, long id);
}

package com.sixbean.testpike.admin.suites.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.suites.api.dto.SuiteCreateRequest;
import com.sixbean.testpike.admin.suites.api.dto.SuiteRunResultView;
import com.sixbean.testpike.admin.suites.api.dto.SuiteUpdateRequest;
import com.sixbean.testpike.admin.suites.api.dto.SuiteView;


public interface CoreSuiteClient {

    
    SuiteView create(SuiteCreateRequest request);

    
    SuiteView update(long id, SuiteUpdateRequest request);

    
    SuiteView get(long id);

    
    PageView<SuiteView> list(long projectId, String keyword, Integer status, int page, int size,
                             String sortBy, String sortDir);

    
    void delete(long id);

 
    SuiteRunResultView run(long id);
}

package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockView;


public interface AppautoWxMockService {

    
    AppautoWxMockView generate(AppautoWxMockRequest request);
}

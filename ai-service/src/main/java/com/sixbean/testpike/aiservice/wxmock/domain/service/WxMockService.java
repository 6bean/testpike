package com.sixbean.testpike.aiservice.wxmock.domain.service;

import com.sixbean.testpike.aiservice.wxmock.api.dto.WxMockRequest;

import java.util.Map;


public interface WxMockService {

    
    Map<String, Object> generate(WxMockRequest request);
}

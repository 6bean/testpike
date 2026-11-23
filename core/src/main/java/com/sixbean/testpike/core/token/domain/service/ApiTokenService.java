package com.sixbean.testpike.core.token.domain.service;

import com.sixbean.testpike.core.token.api.dto.ApiTokenCreateInput;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreated;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyResult;
import com.sixbean.testpike.core.token.api.dto.ApiTokenView;

import java.util.List;


public interface ApiTokenService {

    
    ApiTokenCreated create(Long userId, ApiTokenCreateInput input);

 
    List<ApiTokenView> listByUser(Long userId);

    
    void revoke(Long id, Long operatorId);

    
    ApiTokenVerifyResult verify(String plaintext);
}

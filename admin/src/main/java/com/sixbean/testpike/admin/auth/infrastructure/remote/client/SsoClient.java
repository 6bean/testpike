package com.sixbean.testpike.admin.auth.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.api.dto.SsoUser;



public interface SsoClient {

    
    String authorizeUrl(String redirectUri);

    
    SsoUser loginByCode(String code);

    
    void logout(String token);
}

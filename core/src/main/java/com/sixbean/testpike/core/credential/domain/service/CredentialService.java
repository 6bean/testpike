package com.sixbean.testpike.core.credential.domain.service;

import com.sixbean.testpike.core.credential.api.dto.CredentialInfo;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;

import java.util.List;


public interface CredentialService {

    
    long create(long projectId, CredentialInput input);

    
    void update(long id, CredentialInput input);

    
    CredentialInfo get(long id);

    
    List<CredentialInfo> list(long projectId, Boolean enabledOnly);

    
    void setEnabled(long id, boolean enabled);

    
    void delete(long id);

    
    String revealSecret(long id);

    
    String resolveSecret(long projectId, String credKey);
}

package com.sixbean.testpike.core.storage.domain.service;

import com.sixbean.testpike.common.storage.LocalStorageProvider;


public interface LocalStorageFileService {

    
    byte[] download(String key, long expiresEpochSec, String signature);

    
    void upload(String key, long expiresEpochSec, String signature, byte[] content);

    
    LocalStorageProvider provider();
}

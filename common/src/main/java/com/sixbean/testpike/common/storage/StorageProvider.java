package com.sixbean.testpike.common.storage;

import java.time.Duration;
import java.util.List;


public interface StorageProvider {

    
    String name();

    
    void put(String key, byte[] content, String contentType);

    
    byte[] get(String key);

    
    boolean exists(String key);

    
    StorageGrant presignPut(String key, Duration ttl);

    
    StorageGrant presignGet(String key, Duration ttl);

    
    void delete(String key);

    
    List<String> list(String prefix, int limit);
}

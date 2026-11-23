package com.sixbean.testpike.admin.auth.infrastructure.remote.client;


public interface ConfigValueClient {

    
    String getValue(String key, String defaultValue);
}

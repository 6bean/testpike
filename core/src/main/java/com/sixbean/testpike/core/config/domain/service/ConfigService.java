package com.sixbean.testpike.core.config.domain.service;

import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;

import java.util.List;


public interface ConfigService {

    
    ConfigInfo create(Long operatorId, ConfigInput input);

    
    ConfigInfo update(Long operatorId, long id, ConfigInput input);

    
    void delete(Long operatorId, long id);

    
    List<ConfigInfo> list(String groupName);

    
    String getValue(String configKey, String defaultValue);
}

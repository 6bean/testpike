package com.sixbean.testpike.admin.config.infrastructure.remote.client;

import com.sixbean.testpike.admin.config.api.dto.ConfigSaveRequest;
import com.sixbean.testpike.admin.config.api.dto.ConfigView;
import com.sixbean.testpike.admin.config.api.dto.RetentionItemView;

import java.util.List;


public interface ConfigClient {

    
    List<ConfigView> list(String groupName);

    
    ConfigView create(ConfigSaveRequest request);

    
    ConfigView update(long id, ConfigSaveRequest request);

    
    void delete(long id);

    
    List<RetentionItemView> retentionPreview();

    
    List<RetentionItemView> retentionRun();
}

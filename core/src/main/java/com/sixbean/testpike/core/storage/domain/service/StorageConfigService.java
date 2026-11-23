package com.sixbean.testpike.core.storage.domain.service;

import com.sixbean.testpike.core.storage.api.dto.StorageConfigInput;
import com.sixbean.testpike.core.storage.api.dto.StorageConfigView;


public interface StorageConfigService {

    
    StorageConfigView get();

 
    StorageConfigView update(Long operatorId, StorageConfigInput input);
}

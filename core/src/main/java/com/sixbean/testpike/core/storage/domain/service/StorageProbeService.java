package com.sixbean.testpike.core.storage.domain.service;

import com.sixbean.testpike.core.storage.api.dto.StorageProbeRequest;
import com.sixbean.testpike.core.storage.api.dto.StorageProbeResult;


public interface StorageProbeService {

    
    StorageProbeResult probe(StorageProbeRequest request);
}

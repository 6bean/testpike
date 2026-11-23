package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.core.monitor.api.dto.MonitorLinkedAssetsView;


public interface MonitorLinkedAssetService {

    
    MonitorLinkedAssetsView linkedAssets(long envId);
}

package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformUpdateInput;

import java.util.List;


public interface AppautoPlatformService {

    
    List<AppautoPlatformInfo> listPlatforms(boolean includeDisabled);

    
    AppautoPlatformInfo getByCode(String code);

 
    void update(String code, AppautoPlatformUpdateInput input);
}

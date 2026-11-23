package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineUpdateInput;

import java.util.List;


public interface AppautoEngineService {

    
    List<AppautoEngineInfo> listEngines(boolean includeDisabled);

    
    AppautoEngineInfo getByCode(String code);

    
    void update(String code, AppautoEngineUpdateInput input);
}

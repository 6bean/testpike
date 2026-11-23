package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoSupportView;

import java.util.List;


public interface AppautoSupportService {

    
    List<AppautoSupportView> support(long projectId);
}

package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPackageGrantView;

import java.util.List;


public interface AppautoAppService {

    
    long create(long projectId, AppautoAppInput input);

    
    AppautoAppInfo get(long id);

    
    List<AppautoAppInfo> list(long projectId);

    
    String uploadPackage(long id, byte[] content, String fileName);

    
    void delete(long id);

    
    AppautoPackageGrantView grantPackageDownload(long taskId, Object artifactRef);
}

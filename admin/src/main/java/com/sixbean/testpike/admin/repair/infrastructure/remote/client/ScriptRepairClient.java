package com.sixbean.testpike.admin.repair.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.repair.api.dto.ScriptRepairView;


public interface ScriptRepairClient {

    
    PageView<ScriptRepairView> list(Long projectId, Long caseId, int page, int size);
}

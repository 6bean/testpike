package com.sixbean.testpike.admin.deletecheck.infrastructure.remote.client;

import com.sixbean.testpike.admin.deletecheck.api.dto.DeleteCheckView;


public interface CoreDeleteCheckClient {

    
    DeleteCheckView checkProject(long projectId);

    
    DeleteCheckView checkEnv(long envId);

    
    DeleteCheckView checkRole(long roleId);

    
    DeleteCheckView checkPlan(long planId);

    
    DeleteCheckView checkTag(long tagId);

    
    DeleteCheckView checkCase(long caseId);
}

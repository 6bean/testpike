package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanCreateRequest;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanView;

import java.util.List;


public interface CoreUpgradePlanClient {

    
    UpgradePlanView create(UpgradePlanCreateRequest request);

    
    UpgradePlanImpactView impact(UpgradePlanCreateRequest request);

    
    List<UpgradePlanView> listAll();

    
    UpgradePlanView get(long planId);

    
    List<UpgradePlanStepView> steps(long planId);

    UpgradePlanView start(long planId);

    /** Second-person review confirmation (reviewer = login-context username, bridged as query param). */
    UpgradePlanView review(long planId, String reviewer);

    UpgradePlanView pause(long planId);

    UpgradePlanView resume(long planId);

    UpgradePlanView abort(long planId);
}

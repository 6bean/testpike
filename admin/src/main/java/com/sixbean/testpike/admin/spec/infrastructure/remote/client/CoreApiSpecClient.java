package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecView;
import com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionView;

import java.util.List;


public interface CoreApiSpecClient {

    
    ApiSpecView upload(long projectId, String name, byte[] file);

 
    ApiSpecView fetchFromUrl(long projectId, String name, String url);

    
    PageView<ApiSpecView> list(long projectId, int page, int size);

    
    ApiSpecView get(long id);

    
    List<ApiSpecView> history(long projectId, String name);

    /** Create a subscription (set-internal reference source fields pass through the
     *  bridge untouched; duplicate-name 409 and invalid-address 400 stay in the core
     *  service single source). */
    SpecSubscriptionView createSubscription(long projectId, String name, String url,
                                            Integer checkIntervalMinutes, String sourceKind,
                                            Long providerProjectId, String providerSpecName);

    /** Edit a subscription (URL swap resets the due time so the next scan pulls
     *  immediately; null/blank fields keep current values; kind/provider fields flow
     *  to the core update single source). */
    SpecSubscriptionView updateSubscription(long id, String name, String url,
                                            Integer checkIntervalMinutes, Boolean enabled,
                                            String sourceKind, Long providerProjectId,
                                            String providerSpecName);

    
    SpecSubscriptionView getSubscription(long id);

    
    PageView<SpecSubscriptionView> listSubscriptions(long projectId, int page, int size);

    
    void deleteSubscription(long id);
}

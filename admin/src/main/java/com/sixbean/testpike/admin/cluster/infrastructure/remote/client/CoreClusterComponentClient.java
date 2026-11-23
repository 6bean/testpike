package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterComponentView;

import java.util.List;


public interface CoreClusterComponentClient {

    
    List<ClusterComponentView> listComponents();
}

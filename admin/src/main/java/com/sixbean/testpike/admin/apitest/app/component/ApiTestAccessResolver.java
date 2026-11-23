package com.sixbean.testpike.admin.apitest.app.component;

import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiCollectionClient;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiRequestClient;
import org.springframework.stereotype.Component;


@Component
public class ApiTestAccessResolver {

    private final CoreApiCollectionClient collectionClient;
    private final CoreApiRequestClient requestClient;

    public ApiTestAccessResolver(CoreApiCollectionClient collectionClient,
                                 CoreApiRequestClient requestClient) {
        this.collectionClient = collectionClient;
        this.requestClient = requestClient;
    }

    
    public long projectIdOfCollection(long collectionId) {
        return collectionClient.getCollection(collectionId).projectId();
    }

    
    public long projectIdOfRequest(long requestId) {
        return projectIdOfCollection(requestClient.getRequest(requestId).collectionId());
    }
}

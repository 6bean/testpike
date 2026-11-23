package com.sixbean.testpike.core.lease.domain.service;

import java.util.List;


public interface ResourceLeaseService {

    
    void acquire(String kind, String ref, Long projectId, Long envId, Long batchId, String holderId);

    
    boolean renew(String kind, String ref, String holderId);

    
    void release(String kind, String ref, String holderId);

    
    List<String> sweepExpired(String kind);

    
    String activeHolder(String kind, String ref);
}

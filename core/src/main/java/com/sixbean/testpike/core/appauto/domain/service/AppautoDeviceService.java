package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoLeaseRunView;

import java.util.List;


public interface AppautoDeviceService {

    
    long register(Long projectId, AppautoDeviceInput input);

    
    void update(long id, AppautoDeviceInput input);

    
    AppautoDeviceInfo get(long id);

    
    List<AppautoDeviceInfo> list(long projectId, String label);

    
    void recordProbeResult(long id, boolean success);

    
    int probeRemoteDevices();

    
    void unquarantine(long id);

    
    void delete(long id);

    
    void acquireLease(long deviceId, Long batchId, String holderExecutorId);

    
    void acquireLease(long deviceId, Long borrowerProjectId, Long batchId, String holderExecutorId);

    
    void releaseLease(long deviceId, String holderExecutorId, String reason);

    
    boolean renewLease(long deviceId, String holderExecutorId);

    
    int sweepExpiredDeviceLeases();

 
    List<AppautoLeaseRunView> listLeaseRuns(long deviceId);
}

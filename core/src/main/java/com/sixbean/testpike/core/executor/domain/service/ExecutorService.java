package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorSelfCheckView;

import java.util.List;
import java.util.Map;


public interface ExecutorService {

    
    long register(ExecutorRegisterInput input);

    
    void heartbeat(long executorId, int runningTasks);

    
    List<ExecutorInfo> listAll();

    
    ExecutorInfo get(long executorId);

    
    boolean isOnline(long executorId);

    
    int detectOfflineAndNotify();

    
    boolean hasOnlineExecutor();

    
    List<ExecutorInfo> listOnline();

    
    List<Map<String, Object>> onlineCapabilities();

    
    void releaseCapacity(long executorId);

    
    ExecutorSelfCheckView selfCheck(long executorId);
}

package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.core.appauto.api.dto.TakeoverCreateInput;

import java.util.List;
import java.util.Map;


public interface AppautoTakeoverService {

 
    String STATE_PENDING = "pending";
    String STATE_REJECTED = "rejected";
    String STATE_FORCED = "forced";
    String STATE_ACTIVE = "active";
    String STATE_ENDED = "ended";
    String STATE_EXPIRED = "expired";

    
    AppautoTakeoverView request(TakeoverCreateInput input);

    
    AppautoTakeoverView agree(long requestId, long deciderId);

    
    AppautoTakeoverView reject(long requestId, long deciderId);

    
    AppautoTakeoverView force(long requestId, long adminOperatorId);

 
    AppautoTakeoverView end(long requestId, long operatorId);

    
    AppautoTakeoverView get(long requestId);


    
    String inputQueueKey(long requestId);

    
    void enqueueInput(long requestId, long operatorId, List<Map<String, Object>> commands);

    
    List<Map<String, Object>> pollInputCommands(long requestId, long executorId, int limit);

    
    List<Map<String, Object>> pollInputCommandsForExecutor(long executorId, int limit);
}

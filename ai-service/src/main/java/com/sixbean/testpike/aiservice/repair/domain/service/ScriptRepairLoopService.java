package com.sixbean.testpike.aiservice.repair.domain.service;

import com.sixbean.testpike.common.mq.ExecFailedEventMessage;


public interface ScriptRepairLoopService {

    
    void repair(ExecFailedEventMessage message);
}

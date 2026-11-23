package com.sixbean.testpike.aiservice.judge.domain.service;

import com.sixbean.testpike.common.mq.JudgeSampleMessage;


public interface JudgeOrchestrationService {

    
    void judge(JudgeSampleMessage message);
}

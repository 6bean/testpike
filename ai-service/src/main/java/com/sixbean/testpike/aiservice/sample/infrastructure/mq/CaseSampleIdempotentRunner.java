package com.sixbean.testpike.aiservice.sample.infrastructure.mq;

import com.sixbean.testpike.aiservice.sample.domain.service.TrainingSampleService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.springframework.stereotype.Component;


@Component
public class CaseSampleIdempotentRunner {

    private final TrainingSampleService trainingSampleService;

    public CaseSampleIdempotentRunner(TrainingSampleService trainingSampleService) {
        this.trainingSampleService = trainingSampleService;
    }

    
    @Idempotent(key = "'sample-ingest:case:' + #message.caseId() + ':' + #message.caseVersion() + ':' + #message.action()")
    public String run(CaseIngestEventMessage message, String token) {
        trainingSampleService.captureSample(message);
        return token;
    }
}

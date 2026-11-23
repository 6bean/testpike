package com.sixbean.testpike.aiservice.classify.infrastructure.mq;

import com.sixbean.testpike.aiservice.classify.domain.service.FailureClassifyService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import org.springframework.stereotype.Component;


@Component
public class ClassifyIdempotentRunner {

    private final FailureClassifyService failureClassifyService;

    public ClassifyIdempotentRunner(FailureClassifyService failureClassifyService) {
        this.failureClassifyService = failureClassifyService;
    }

    
    @Idempotent(key = "'exec-classify:task:' + #message.execTaskId()")
    public String run(ExecFailedEventMessage message, String token) {
        failureClassifyService.classify(message);
        return token;
    }
}

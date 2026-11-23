package com.sixbean.testpike.aiservice.judge.infrastructure.mq;

import com.sixbean.testpike.aiservice.judge.domain.service.JudgeOrchestrationService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import org.springframework.stereotype.Component;


@Component
public class JudgeIdempotentRunner {

    private final JudgeOrchestrationService orchestrationService;

    public JudgeIdempotentRunner(JudgeOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    
    @Idempotent(key = "'ai-judge:' + #message.runId() + ':' + #message.sampleId()")
    public String judge(JudgeSampleMessage message, String token) {
        orchestrationService.judge(message);
        return token;
    }
}

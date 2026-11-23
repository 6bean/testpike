package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.domain.service.AiGenService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.springframework.stereotype.Component;


@Component
public class AiGenIdempotentRunner {

    private final AiGenService aiGenService;

    public AiGenIdempotentRunner(AiGenService aiGenService) {
        this.aiGenService = aiGenService;
    }

    
    @Idempotent(key = "'ai-gen:' + #message.taskId() + ':' + #message.taskType() + ':' + #message.genSeq()")
    public String run(AiGenMessage message, String token) {
        aiGenService.generate(message);
        return token;
    }
}

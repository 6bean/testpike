package com.sixbean.testpike.aiservice.spec.infrastructure.mq;

import com.sixbean.testpike.aiservice.spec.domain.service.SpecDraftExtractService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import org.springframework.stereotype.Component;


@Component
public class SpecExtractIdempotentRunner {

    private final SpecDraftExtractService specDraftExtractService;

    public SpecExtractIdempotentRunner(SpecDraftExtractService specDraftExtractService) {
        this.specDraftExtractService = specDraftExtractService;
    }

    
    @Idempotent(key = "'ai-spec-extract:' + #message.documentId() + ':' + #message.publishToken()")
    public String run(AiSpecExtractMessage message, String token) {
        specDraftExtractService.extract(message);
        return token;
    }
}

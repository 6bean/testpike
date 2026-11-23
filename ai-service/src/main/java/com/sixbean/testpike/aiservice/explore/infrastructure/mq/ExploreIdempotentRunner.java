package com.sixbean.testpike.aiservice.explore.infrastructure.mq;

import com.sixbean.testpike.aiservice.explore.domain.service.ExploreRunService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import org.springframework.stereotype.Component;


@Component
public class ExploreIdempotentRunner {

    private final ExploreRunService exploreRunService;

    public ExploreIdempotentRunner(ExploreRunService exploreRunService) {
        this.exploreRunService = exploreRunService;
    }

    
    @Idempotent(key = "'ai-explore:' + #message.sessionId()")
    public String run(ExploreRunMessage message, String token) {
        exploreRunService.run(message);
        return token;
    }
}

package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import org.springframework.stereotype.Component;


@Component
public class FcRoundPolicy {

 
    public static final String KEY = "ai.tool.fc-max-rounds";

 
    static final int DEFAULT_MAX_ROUNDS = 5;

    private final CoreGateway coreGateway;

    public FcRoundPolicy(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    
    public int maxRounds() {
        return SysConfigInts.positiveInt(coreGateway.getSysConfig(KEY), DEFAULT_MAX_ROUNDS);
    }
}

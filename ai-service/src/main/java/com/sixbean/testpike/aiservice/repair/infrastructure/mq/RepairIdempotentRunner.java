package com.sixbean.testpike.aiservice.repair.infrastructure.mq;

import com.sixbean.testpike.aiservice.repair.domain.service.ScriptRepairLoopService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import org.springframework.stereotype.Component;


@Component
public class RepairIdempotentRunner {

    private final ScriptRepairLoopService scriptRepairLoopService;

    public RepairIdempotentRunner(ScriptRepairLoopService scriptRepairLoopService) {
        this.scriptRepairLoopService = scriptRepairLoopService;
    }

    
    @Idempotent(key = "'exec-repair:task:' + #message.execTaskId()")
    public String run(ExecFailedEventMessage message, String token) {
        scriptRepairLoopService.repair(message);
        return token;
    }
}

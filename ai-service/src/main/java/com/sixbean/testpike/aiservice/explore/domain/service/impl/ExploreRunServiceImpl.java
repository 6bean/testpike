package com.sixbean.testpike.aiservice.explore.domain.service.impl;

import com.sixbean.testpike.aiservice.explore.domain.component.ExploreLoopKernel;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskFailureEvent;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskFailureEventFormatter;
import com.sixbean.testpike.aiservice.explore.domain.service.ExploreRunService;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


@Service
public class ExploreRunServiceImpl implements ExploreRunService {

    private static final Logger log = LoggerFactory.getLogger(ExploreRunServiceImpl.class);

    private final CoreGateway coreGateway;
    private final ExploreLoopKernel loopKernel;

    /** supplementary clause (failure must feed back): terminal failure
 * event door. Null = legacy zero-report; the exception itself always
 * propagates (MQ retry / dead-letter semantics stay untouched). */
    private com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore
            processEvents;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProcessEvents(
            com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore store) {
        this.processEvents = store;
    }

    public ExploreRunServiceImpl(CoreGateway coreGateway, ExploreLoopKernel loopKernel) {
        this.coreGateway = coreGateway;
        this.loopKernel = loopKernel;
    }

    @Override
    public void run(ExploreRunMessage message) {
        try {
            ExploreSessionBrief session = coreGateway.getExploreSession(message.sessionId());
            if (session == null) {
                // core's normal path never answers null (a missing session is a
                // 40401 error); this guard makes contract drift visible — never silent
                throw new IllegalStateException("探索会话回读为空 sessionId=" + message.sessionId());
            }
            if (session.isTerminal()) {
                log.info("B9 探索会话已终态跳过 sessionId={} status={}",
                        session.id(), session.status());
                return;
            }
            loopKernel.run(session, message.operatorId());
        } catch (RuntimeException failure) {
            // A run that dies mid-flight must still end its event tail with a
            // human-readable failure classification (never a silent stop):
            // land the shaped terminal event first, then rethrow unchanged so
            // container retry / dead-letter semantics are untouched.
            recordTerminalFailureEvent(message.sessionId(), failure);
            throw failure;
        }
    }

    /** Land the shaped terminal failure line through the single-source
     *  formatter (the same category + suggestion + retry-entry face every
     *  job family ends with). */
    private void recordTerminalFailureEvent(long sessionId, RuntimeException failure) {
        if (processEvents == null) {
            return;
        }
        TaskFailureEvent terminal = TaskFailureEventFormatter.format(
                failure, "探索会话页重新发起探索");
        String anchor = String.valueOf(sessionId);
        processEvents.record(ExploreLoopKernel.PROCESS_JOB_KIND, anchor, anchor,
                "error", terminal.summary(), terminal.detailJson());
    }
}

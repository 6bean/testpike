package com.sixbean.testpike.aiservice.doc.infrastructure.lifecycle;

import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightEntry;
import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightRegistry;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * on graceful shutdown, write every in-flight extraction generation
 * back as failed through the existing core markFailed endpoint (generation-token
 * guard reused, zero new channel). Runs at the highest lifecycle phase so it stops —
 * and therefore writes back — before connection pools and Redis are released.
 * Deliberately NOT a Runtime shutdown hook: the dirty-shutdown log loss
 * makes raw hooks unprovable here.
 */
@Component
public class DocExtractShutdownWriteBack implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DocExtractShutdownWriteBack.class);

    /** Human extract_error face: what happened and what the user can do next. */
    static final String INTERRUPT_MESSAGE = "服务重启中断了本次提取，请稍后重新发起提取";

    private final DocExtractInFlightRegistry registry;
    private final CoreGateway coreGateway;
    private volatile boolean running;

    public DocExtractShutdownWriteBack(DocExtractInFlightRegistry registry,
                                       CoreGateway coreGateway) {
        this.registry = registry;
        this.coreGateway = coreGateway;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        for (DocExtractInFlightEntry generation : registry.drain()) {
            try {
                coreGateway.markExtractionFailed(generation.documentId(),
                        INTERRUPT_MESSAGE, generation.extractToken());
            } catch (RuntimeException writeBackFailure) {
                // Honest naming in the log: a failed write-back must never swallow
                // the remaining generations nor masquerade as a clean shutdown.
                log.error("停机回写提取失败状态未送达 documentId={} 原因={}",
                        generation.documentId(), writeBackFailure.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}

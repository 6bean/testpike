package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counting stand-in for the extraction service in the consume-path tests:
 * records how many deliveries actually reached the service seam.
 */
class ExtractCallCounter implements DocExtractService {

    final AtomicInteger calls = new AtomicInteger();

    @Override
    public void extract(AiDocExtractMessage message) {
        calls.incrementAndGet();
    }

    @Override
    public void handleTerminalFailure(AiDocExtractMessage message, Exception escapingFailure) {
        // Consume-path fixtures pin the extract seam only; the terminal hook's
        // own wiring and content live in the dedicated fixtures of this batch.
    }
}

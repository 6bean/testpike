package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightEntry;
import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightRegistry;
import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.springframework.stereotype.Component;


@Component
public class AiDocExtractIdempotentRunner {

    private final DocExtractService docExtractService;
    /** seam: null (legacy single-arg assembly) = no in-flight tracking. */
    private final DocExtractInFlightRegistry inFlightRegistry;

    public AiDocExtractIdempotentRunner(DocExtractService docExtractService) {
        this(docExtractService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiDocExtractIdempotentRunner(DocExtractService docExtractService,
                                       DocExtractInFlightRegistry inFlightRegistry) {
        this.docExtractService = docExtractService;
        this.inFlightRegistry = inFlightRegistry;
    }

    /**
 * inflightSeconds sizes the E: in-flight marker : a crashed
 * holder's orphan stamp must expire with twice the consume lease, not with
 * the 24h done-window; the D: replay anchor keeps the annotation window. */
    @Idempotent(key = "'ai-doc-extract:' + #message.documentId() + ':' + #message.extractToken()",
            inflightTtl = "#inflightSeconds")
    public String run(AiDocExtractMessage message, String token, long inflightSeconds) {
        // register the generation for the whole real execution
        // (this body only runs once the idempotency aspect admits the delivery),
        // release it in every terminal exit including a thrown failure. Legacy
        // single-arg assembly keeps the seam absent = byte-identical behaviour.
        DocExtractInFlightEntry generation = inFlightRegistry == null ? null
                : new DocExtractInFlightEntry(message.documentId(), message.extractToken());
        if (generation != null) {
            inFlightRegistry.register(generation);
        }
        try {
            docExtractService.extract(message);
            return token;
        } finally {
            if (generation != null) {
                inFlightRegistry.unregister(generation.documentId(), generation.extractToken());
            }
        }
    }
}

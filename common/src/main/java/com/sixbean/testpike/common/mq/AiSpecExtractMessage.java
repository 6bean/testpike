package com.sixbean.testpike.common.mq;


public record AiSpecExtractMessage(long documentId, long projectId, long operatorId,
                                   String modelHint, String publishToken) {

    
    public static final String KEY_MAX_CHUNKS = "apitest.spec-extract.max-chunks";

    
    public static final int DEFAULT_MAX_CHUNKS = 50;

    /**
 * grouped-intake door key (factory on). Both the core dispatch
 * pre-check and the ai-service consumer door read this one key, so the
 * pre-check can never reject what the consumer would have grouped —
 * single source for the key literal (R40).
 */
    public static final String KEY_GROUPED_INTAKE_ENABLED = "ai.spec.grouped-intake.enabled";
}

package com.sixbean.testpike.common.mq;

/**
 *
 * @param dimensionHint -3b targeted dimension name (nullable; whitelist
 * = the core suggester vocabulary, validated before publish —
 * arbitrary body text can never ride the message into the prompt)
 * @param chunkIds segment B directed-supplement whitelist (nullable; when
 * non-empty the consumer runs exactly one round over these
 * chunks of this document instead of the whole text — ids are
 * validated against the document's own chunk rows before the
 * message is ever published, so a foreign id cannot ride here;
 * null/empty = the historical full-document behaviour)
 */
public record AiDocExtractMessage(long documentId, long projectId, long operatorId,
                                  String modelHint, String extractToken, String dimensionHint,
                                  java.util.List<Long> chunkIds) {

    /**
     * Legacy five-argument builder kept for the two-phase migration: existing
     * producers and pre-hint payloads run with dimensionHint = null, which the
     * ai-service renders as the byte-identical plain extraction prompt.
     */
    public AiDocExtractMessage(long documentId, long projectId, long operatorId,
                               String modelHint, String extractToken) {
        this(documentId, projectId, operatorId, modelHint, extractToken, null, null);
    }

    /** Pre-supplement six-argument builder (two-phase migration: full-document run). */
    public AiDocExtractMessage(long documentId, long projectId, long operatorId,
                               String modelHint, String extractToken, String dimensionHint) {
        this(documentId, projectId, operatorId, modelHint, extractToken, dimensionHint, null);
    }
}

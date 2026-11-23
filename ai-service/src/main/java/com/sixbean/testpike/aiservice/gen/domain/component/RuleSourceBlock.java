package com.sixbean.testpike.aiservice.gen.domain.component;

/**
 * One anchored original-text block fed to {@link RuleItemExtractor}
 * . {@code chunkId} keeps block attribution so every extracted
 * item can point back at the document chunk it was read from.
 *
 * @param chunkId id of the source document chunk
 * @param text raw block text (never re-written by the extractor)
 */
public record RuleSourceBlock(long chunkId, String text) {
}

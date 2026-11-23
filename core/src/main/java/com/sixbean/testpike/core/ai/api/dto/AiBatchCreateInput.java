package com.sixbean.testpike.core.ai.api.dto;

import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.aibatch.BatchItemNormalizer;

import java.util.List;

/**
 * Input parameters for the AI batch generation service (batch creation; operatorId is passed through by the server from X-Operator-Id to complete the audit loop).
 * <p>BA batch : the canonical component is the structured item list; plain-text callers go
 * through the {@link #fromTexts} factory (generic erasure forbids an overloaded constructor).
 *
 * @param projectId Project ID
 * @param operatorId Initiator (passed through from X-Operator-Id)
 * @param items structured batch entries (each text non-empty and ≤5000; optional requirement link)
 * @param windowFeedDeclared caller-declared verification-window feed (server still
 * checks the hot switch and the project whitelist before honoring it)
 */
public record AiBatchCreateInput(long projectId, long operatorId, List<AiBatchItem> items,
                                 boolean windowFeedDeclared) {

    /** Legacy three-argument call sites keep compiling (no window declaration). */
    public AiBatchCreateInput(long projectId, long operatorId, List<AiBatchItem> items) {
        this(projectId, operatorId, items, false);
    }

    /** Plain-text (legacy) entry point: every text becomes an unlinked item via the shared normalizer. */
    public static AiBatchCreateInput fromTexts(long projectId, long operatorId, List<String> texts) {
        return new AiBatchCreateInput(projectId, operatorId, BatchItemNormalizer.normalize(null, texts));
    }

    /** Plain-text entry point carrying the window-feed declaration. */
    public static AiBatchCreateInput fromTexts(long projectId, long operatorId, List<String> texts,
                                               boolean windowFeedDeclared) {
        return new AiBatchCreateInput(projectId, operatorId,
                BatchItemNormalizer.normalize(null, texts), windowFeedDeclared);
    }
}

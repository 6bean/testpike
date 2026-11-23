package com.sixbean.testpike.api.aichat.infrastructure.remote.client;

import com.sixbean.testpike.api.aichat.api.dto.ChatShareSnapshotMirrorView;

/**
 * core dialog-share bridge (AO batch P2 ; api→HTTP→core
 * /internal/ai-chat/shares/{token}, service-layer rule — the public door
 * never touches the ledger directly).
 */
public interface CoreChatShareClient {

    /** Frozen-copy read path (GET /internal/ai-chat/shares/{shareToken}, no operator semantics). */
    ChatShareSnapshotMirrorView openShare(String shareToken);
}

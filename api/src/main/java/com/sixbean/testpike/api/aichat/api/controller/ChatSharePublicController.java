package com.sixbean.testpike.api.aichat.api.controller;

import com.sixbean.testpike.api.aichat.api.dto.ChatShareSnapshotMirrorView;
import com.sixbean.testpike.api.aichat.infrastructure.remote.client.CoreChatShareClient;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public dialog-snapshot share door (AO batch P2 , 02 contract
 * GET /open/chat-share/{shareToken}; TC-AO-20).
 * <p><b>Red line ⑤ posture</b>: public = unauthenticated read, zero side
 * effects beyond core's lazy self-destruct on an expired touch. The token is
 * a non-enumerable UUID (V332), never a session id — nothing on this URL
 * aims at the live ledger. The api service carries no Spring Security (CI
 * badge precedent), so this controller is naturally the public face; the
 * admin PublicEndpointRegistry CHAT_SHARE_VIEW entry feeds the contract
 * consistency gate.
 * <p>Cache-Control: no-store (unlike the badge's 60s public cache): a share
 * can be revoked or expire at any minute, and a shared cache would keep
 * serving a copy its owner already killed. Garbage token 400 / dead link 404
 * sentences pass through from core verbatim.
 */
@RestController
public class ChatSharePublicController {

    private final CoreChatShareClient shareClient;

    public ChatSharePublicController(CoreChatShareClient shareClient) {
        this.shareClient = shareClient;
    }

    /** The frozen copy a share link serves, markdown included (never truncated). */
    @GetMapping("/open/chat-share/{shareToken}")
    public ResponseEntity<ChatShareSnapshotMirrorView> share(@PathVariable String shareToken) {
        ChatShareSnapshotMirrorView view = shareClient.openShare(shareToken);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(view);
    }
}

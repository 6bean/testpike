package com.sixbean.testpike.api.aichat.api.controller;

import com.sixbean.testpike.api.aichat.api.dto.ChatShareSnapshotMirrorView;
import com.sixbean.testpike.api.aichat.infrastructure.remote.client.CoreChatShareClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public share door assembly surface (AO batch P2 , TC-AO-20
 * backend half): whoever holds the frozen-copy link reads it with the token
 * alone. Same posture as {@code CiBadgeControllerTest} — a recording stub
 * isolates the core call (the real api→core→DB leg is covered L4-live,
 * verification layers must not skip).
 * <p>Asserted surface: ①the token rides to core verbatim — this door
 * translates, it never judges; ②the snapshot body carries title+markdown+turn
 * count in full (information complete, never truncated — red line 10); ③cache
 * policy is no-store: unlike the badge (60s public cache) a share can be
 * revoked at any minute and a front cache would keep serving a killed copy;
 * ④core's refusal sentence rides out untouched — the one-404-sentence
 * anti-enumeration rule stays single-sourced in core.
 */
class ChatSharePublicControllerTest {

    /** Recording stub: captures the token and replays one fixed view. */
    private static final class StubClient implements CoreChatShareClient {
        private String lastToken;
        private ChatShareSnapshotMirrorView next = new ChatShareSnapshotMirrorView(
                "分享试验", "## 问\n查一下账号\n\n## 答\n手机号在记录里", 1,
                LocalDateTime.of(2026, 11, 2, 12, 0));

        @Override
        public ChatShareSnapshotMirrorView openShare(String shareToken) {
            this.lastToken = shareToken;
            return next;
        }
    }

    private final StubClient client = new StubClient();
    private final ChatSharePublicController controller = new ChatSharePublicController(client);

    @Test
    void tokenRidesToCoreVerbatim_andBodyCarriesFrozenCopyInFull() {
        ResponseEntity<ChatShareSnapshotMirrorView> response =
                controller.share("2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1");
        assertEquals("2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1", client.lastToken);
        ChatShareSnapshotMirrorView body = response.getBody();
        assertTrue(body != null && body.markdown().contains("手机号在记录里"),
                "snapshot rides out in full");
        assertEquals("分享试验", body.title());
        assertEquals(1, body.turnCount());
    }

    @Test
    void cachePolicyIsNoStore_soRevocationBitesOnTheNextTouch() {
        ResponseEntity<ChatShareSnapshotMirrorView> response = controller.share("2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1");
        String cacheControl = String.valueOf(response.getHeaders().getCacheControl());
        assertTrue(cacheControl.contains("no-store"),
                "a revocable public copy must never sit in a shared cache: " + cacheControl);
    }

    @Test
    void coreRefusalRidesOutAsTheSameSentence_NoSecondCopyHere() {
        CoreChatShareClient dead = token -> {
            throw new ServiceException("分享链接不存在、已过期或已被撤回", BizErrorCode.NOT_FOUND);
        };
        ChatSharePublicController door = new ChatSharePublicController(dead);
        ServiceException refusal = assertThrows(ServiceException.class, () -> door.share("unknown-token"));
        // The door adds no sentence of its own: unknown/expired/revoked keep
        // core's single anti-enumeration wording all the way to the browser.
        assertEquals("分享链接不存在、已过期或已被撤回", refusal.getMessage());
        assertEquals(Integer.valueOf(BizErrorCode.NOT_FOUND), refusal.getCode());
    }
}

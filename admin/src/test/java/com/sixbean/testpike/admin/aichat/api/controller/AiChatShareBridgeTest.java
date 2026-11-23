package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.CoreUserProjectClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshot-share admin bridge contract : the dock's
 * share buttons need three doors — freeze a copy, list my copies, revoke one.
 * <p>Asserted surface, same posture as {@link AiChatSessionBridgeTest}:
 * ①the owner id always comes from the login context — no body or query field
 * can speak for another user (a stranger's revoke attempt carries the
 * stranger's own id and core refuses it); ②admin keeps zero validation copies:
 * core's refusal sentences (empty dialog, foreign session, unknown link) pass
 * through untouched; ③every door carries the catalog key perm:aichat:use so
 * signature drift turns red here first; ④the ttl request value is forwarded
 * verbatim — the 1h..7d clamp is core's single source, not a second copy.
 */
class AiChatShareBridgeTest {

    private final CoreAiChatClient client = mock(CoreAiChatClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final CoreUserProjectClient userProjects = mock(CoreUserProjectClient.class);
    private final AiChatSessionController controller =
            new AiChatSessionController(client, guard, userProjects);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u", "name", "dept", List.of("TESTER"), 0L, null));
    }

    private ChatShareMirrorView share(long id, long sessionId) {
        return new ChatShareMirrorView(id, sessionId, "2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1",
                "分享试验", 3, LocalDateTime.of(2026, 11, 2, 12, 0), 0,
                LocalDateTime.of(2026, 10, 30, 12, 0));
    }

    @Test
    void createShareInjectsLoginIdentityAndForwardsTtlVerbatim() {
        login(7L);
        when(client.createShare(eq(11L), eq(7L), anyInt())).thenReturn(share(21L, 11L));

        ChatShareMirrorView got = controller.createShare(11L, 72).getData();

        assertEquals(21L, got.id());
        // 72 is inside the window; the clamp decision belongs to core — admin
        // must forward exactly what the caller asked for, never pre-adjust.
        verify(client).createShare(11L, 7L, 72);
    }

    @Test
    void listSharesCarriesLoginOwnerAndPassesCoreRefusalVerbatim() {
        login(7L);
        when(client.listShares(11L, 7L)).thenReturn(List.of(share(21L, 11L)));
        assertEquals(1, controller.listShares(11L).getData().size());

        // A stranger touching someone else's session is refused core-side; the
        // named sentence rides out with no admin rewrite.
        ServiceException foreign = new ServiceException("该会话不属于当前用户", BizErrorCode.FORBIDDEN);
        when(client.listShares(eq(12L), anyLong())).thenThrow(foreign);
        ServiceException thrown = assertThrows(ServiceException.class, () -> controller.listShares(12L));
        assertEquals("该会话不属于当前用户", thrown.getMessage());
    }

    @Test
    void revokeShareSpeaksOnlyForTheLoggedInOwner() {
        login(7L);
        when(client.revokeShare(eq(21L), eq(7L))).thenReturn(share(21L, 11L));

        ChatShareMirrorView revoked = controller.revokeShare(21L).getData();
        assertEquals(0, revoked.revoked()); // mirror transport only — core flips the flag
        verify(client).revokeShare(21L, 7L);
    }

    @Test
    void everyShareDoorCarriesTheCatalogPermissionKey() throws Exception {
        for (Method m : List.of(
                AiChatSessionController.class.getMethod("createShare", long.class, int.class),
                AiChatSessionController.class.getMethod("listShares", long.class),
                AiChatSessionController.class.getMethod("revokeShare", long.class))) {
            RequirePermission key = m.getAnnotation(RequirePermission.class);
            assertNotNull(key, m.getName() + " 必须挂功能权限点（缺键=check [7/7] 构建期缺口）");
            assertEquals("perm:aichat:use", key.value(), m.getName());
        }
    }

    @Test
    void publicShareDoorIsRegisteredInTheExemptionWhitelist() {
        // Red line ⑤ contract family: the api /open/chat-share/{token} face is
        // exempt from the permission gate and must be an explicit registry
        // entry (badge posture) — missing = check [7/7] build failure.
        assertTrue(com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry
                        .isPublic("GET", "/open/chat-share/2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1"),
                "公开分享读面必须已在豁免面白名单登记");
        assertFalse(com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry
                        .isPublic("POST", "/open/chat-share/x"),
                "公开面只读：POST 形态不得被同一登记豁免");
    }
}

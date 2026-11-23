package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.GroupScopeApplyRequest;
import com.sixbean.testpike.admin.aichat.api.dto.GroupScopeConfirmRequest;
import com.sixbean.testpike.admin.aichat.api.dto.PendingGroupCardView;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Scope-shaped group door bridge contract . The
 * admin side stays thin by rule: pin the login identity, gate the viewer's
 * project segment exactly like the single-card door, assemble the canonical
 * system group wire from the explicit segment coordinates, forward. Shape
 * adjudication stays single-sourced in core (DraftGroupScope lives there);
 * the bridge never grows a second parser — a malformed kind travels
 * unchanged and is refused loudly core-side before any write. The
 * CoreAiChatClient stand-in is L2 bridge isolation only (same precedent
 * family as the pending-impact bridge); the real admin-to-core hop is
 * exercised by the core-side door spectra.
 */
class AiChatGroupScopeBridgeTest {

    private final CoreAiChatClient client = mock(CoreAiChatClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AiChatDraftController controller = new AiChatDraftController(client, guard);

    @BeforeEach
    void login() {
        UserContext.set(new CurrentUser(42L, "tester", "Tester", "QA",
                List.of("USER"), System.currentTimeMillis() / 1000 + 3600, null));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    @Test
    void confirmToken_pinsIdentityAndViewGateOnTheSystemWire() {
        controller.groupScopeConfirmToken(new GroupScopeConfirmRequest(7L, "SCRIPT_REPAIR"));

        verify(guard).assertCanView(any(), eq(7L));
        verify(client).confirmGroupDraftsByScope(42L, "system:7:SCRIPT_REPAIR");
    }

    @Test
    void apply_forwardsTicketAndCheckedIdsOnTheSameWire() {
        controller.groupScopeApply(new GroupScopeApplyRequest(
                7L, "SCRIPT_REPAIR", "tok-1", List.of(11L, 12L)));

        verify(guard).assertCanView(any(), eq(7L));
        verify(client).applyGroupDraftsByScope(42L, "system:7:SCRIPT_REPAIR",
                "tok-1", List.of(11L, 12L));
    }

    @Test
    void pendingRead_gatesOnSliceThenListsCards() {
        when(client.groupPendingCards(42L, "system:7:LINKED_CASE")).thenReturn(List.of(
                new PendingGroupCardView(11L, 7L, "LINKED_CASE", "s", 500L, 2,
                        "v2", "{}", 1)));

        var cards = controller.groupPending(7L, "LINKED_CASE").getData();

        assertEquals(1, cards.size());
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void pendingRead_withoutKind_forwardsTheEnumerationWire() {
        when(client.groupPendingCards(42L, "system:7")).thenReturn(List.of(
                new PendingGroupCardView(11L, 7L, "SCRIPT_REPAIR", "s", 500L, 2,
                        "v2", "{}", 1)));

        var cards = controller.groupPending(7L, null).getData();

        assertEquals(1, cards.size(), "the enumeration answer rides through untouched");
        assertEquals("SCRIPT_REPAIR", cards.get(0).objectType(),
                "each card carries its own group token for the panel's doors");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).groupPendingCards(42L, "system:7");
    }

    @Test
    void foreignProject_refusesBeforeAnyCoreHop() {
        doThrow(new ServiceException("无权限查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(9L));

        assertThrows(ServiceException.class, () -> controller.groupPending(9L, "LINKED_CASE"));
        verifyNoInteractions(client);

        assertThrows(ServiceException.class, () -> controller
                .groupScopeConfirmToken(new GroupScopeConfirmRequest(9L, "LINKED_CASE")));
        verify(client, never()).confirmGroupDraftsByScope(anyLong(), any());
    }

    @Test
    void malformedKind_isLeftToCore_neverReparsedHere() {
        controller.groupScopeConfirmToken(new GroupScopeConfirmRequest(7L, "bad kind!"));

        verify(client).confirmGroupDraftsByScope(42L, "system:7:bad kind!");
    }
}

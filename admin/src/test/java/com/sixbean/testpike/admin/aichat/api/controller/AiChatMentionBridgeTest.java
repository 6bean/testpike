package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.MentionCandidateView;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreMentionAssemblyClient;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.MentionSkillRow;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.MentionToolRow;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mention-candidate bridge contract : the composer's
 * @-palette reads exactly one door — the core assembly sets — merged in the
 * stable order skill-first, and never judges visibility by itself.
 * <p>Asserted surface: ①the H1 project guard runs before any core call, so a
 * non-member is refused with the guard's own sentence and nothing is fetched;
 * ②the tool door is asked WITH the login actor id (the five-way gate verdict
 * belongs to core and the header is the only way to drive it — a header-less
 * call would widen the palette beyond what the run could honour); ③the view
 * shape is the (kind,id,title) triple the wire already canonicalises, so no
 * second payload contract is introduced; ④the lane carries the catalog door
 * key perm:aichat:use (signature drift = red first).
 */
class AiChatMentionBridgeTest {

    private final CoreMentionAssemblyClient client = mock(CoreMentionAssemblyClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AiChatMentionController controller =
            new AiChatMentionController(client, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u", "name", "dept", List.of("TESTER"), 0L, null));
    }

    @Test
    void candidatesMergeSkillFirstAndToolSecondIntoWireTriples() {
        login(7L);
        when(client.listSkillAssembly(3L)).thenReturn(List.of(
                new MentionSkillRow(11L, "冒烟规范", 3, 1, 2, 5, "body")));
        when(client.listToolAssembly(3L, 7L)).thenReturn(List.of(
                new MentionToolRow(21L, "订单查询", 2, 1, "{}", 5000, false)));

        List<MentionCandidateView> out = controller.candidates(3L).getData();

        assertEquals(2, out.size());
        assertEquals("skill", out.get(0).kind());
        assertEquals(11L, out.get(0).id());
        assertEquals("冒烟规范", out.get(0).title());
        assertEquals("tool", out.get(1).kind());
        assertEquals(21L, out.get(1).id());
        assertEquals("订单查询", out.get(1).title());
    }

    @Test
    void nonMemberIsRefusedByTheGuardBeforeAnyCoreFetch() {
        login(7L);
        doThrow(new ServiceException("您不是该项目成员，无权访问该项目资源", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(3L));

        assertThrows(ServiceException.class, () -> controller.candidates(3L));
        verifyNoInteractions(client);
    }

    @Test
    void toolDoorIsAskedWithTheLoginActorIdDrivingTheFiveWayGate() {
        login(7L);
        when(client.listSkillAssembly(3L)).thenReturn(List.of());
        when(client.listToolAssembly(3L, 7L)).thenReturn(List.of());

        controller.candidates(3L);

        verify(client).listToolAssembly(3L, 7L);
        verify(guard).assertCanView(any(), eq(3L));
    }

    @Test
    void emptyAssemblyAnswersAnEmptyPaletteWithoutRefusal() {
        login(7L);
        when(client.listSkillAssembly(3L)).thenReturn(List.of());
        when(client.listToolAssembly(anyLong(), anyLong())).thenReturn(List.of());

        assertTrue(controller.candidates(3L).getData().isEmpty());
    }

    @Test
    void laneCarriesTheCatalogDoorKey() throws Exception {
        Method method = AiChatMentionController.class
                .getMethod("candidates", long.class);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertEquals("perm:aichat:use", permission.value());
    }
}

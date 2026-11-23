package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.ChatDraftView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionPageMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionView;
import com.sixbean.testpike.admin.aichat.api.dto.SessionCreateRequest;
import com.sixbean.testpike.admin.aichat.api.dto.SessionRenameRequest;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.core.api.dto.UserProjectInfo;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.CoreUserProjectClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dialog-session admin bridge contract : the dock needs
 * one door to open a conversation before it attaches to the SSE stream.
 * <p>Asserted surface: ①the operator identity is injected from the login
 * context only — a body field can never speak for the user (horizontal-access
 * red line); ②the H1 project guard runs before any core call, so a non-member
 * is refused with the guard's own sentence and nothing reaches core; ③core's
 * single-point validation messages (blank title, non-object context) pass
 * through verbatim — admin never keeps a second validation copy; ④the lane
 * carries the catalog door key perm:aichat:use (signature drift = red first);
 * ⑤the session source defaults to the dock surface when the caller says
 * nothing, mirroring the floating entry ruling without inventing a fourth one.
 */
class AiChatSessionBridgeTest {

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

    private ChatSessionView session(long id, long projectId, long userId) {
        return new ChatSessionView(id, projectId, userId, "first question", 1, "{}", 1, 0L, 0L);
    }

    // ---------- identity + guard ----------

    @Test
    void createSessionInjectsLoginIdentityAndRunsProjectGuardFirst() {
        login(7L);
        when(client.createSession(eq(3L), eq(7L), any(), eq(1), any()))
                .thenReturn(session(11L, 3L, 7L));

        ChatSessionView got = controller.create(new SessionCreateRequest(3L, "first question", null, null))
                .getData();

        assertEquals(11L, got.id());
        // Operator id is the login user, never anything the browser could send.
        verify(client).createSession(eq(3L), eq(7L), eq("first question"), eq(1), any());
        verify(guard).assertCanView(any(), eq(3L));
    }

    @Test
    void nonMemberIsRefusedByGuardBeforeAnyCoreCall() {
        login(7L);
        doThrow(new ServiceException("您不是该项目成员，无权访问该项目资源", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(3L));

        assertThrows(ServiceException.class,
                () -> controller.create(new SessionCreateRequest(3L, "title", 2, null)));
        verifyNoInteractions(client);
    }

    // ---------- core single-source pass-through ----------

    @Test
    void coreValidationSentencePassesThroughWithoutASecondCopy() {
        login(7L);
        when(client.createSession(anyLong(), anyLong(), any(), anyInt(), any()))
                .thenThrow(new ServiceException("对话上下文必须是 JSON 对象（仅路由/项目/对象标识类字段）",
                        BizErrorCode.VALIDATION_ERROR));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> controller.create(new SessionCreateRequest(3L, " ", 1, "[\"bulk\"]")));
        assertEquals("对话上下文必须是 JSON 对象（仅路由/项目/对象标识类字段）", refusal.getMessage());
    }

    @Test
    void explicitSourceIsForwardedUnchanged() {
        login(9L);
        when(client.createSession(eq(3L), eq(9L), any(), eq(3), any()))
                .thenReturn(session(12L, 3L, 9L));

        controller.create(new SessionCreateRequest(3L, "title", 3, "{\"route\":\"/mock/spaces\"}"));
        verify(client).createSession(eq(3L), eq(9L), eq("title"), eq(3), eq("{\"route\":\"/mock/spaces\"}"));
    }

    // ---------- door key wiring ----------

    @Test
    void sessionLaneCarriesTheDialogDoorKey() throws NoSuchMethodException {
        Method lane = AiChatSessionController.class.getMethod("create", SessionCreateRequest.class);
        RequirePermission ann = lane.getAnnotation(RequirePermission.class);
        assertEquals("perm:aichat:use", ann == null ? null : ann.value(),
                "session-creation lane must require perm:aichat:use");
    }

    // ---------- history reload bridge ----------

    @Test
    void historyAnchorsOnLoginIdentityAndPassesTheLedgerThrough() {
        login(7L);
        when(client.listHistory(11L, 7L)).thenReturn(List.of(
                new ChatMessageView(1L, 11L, "user", "问题", null, "done", null, null, 0, 0,
                        java.time.LocalDateTime.of(2026, 10, 28, 9, 30), null, null, List.of(), null, false),
                new ChatMessageView(2L, 11L, "assistant", "重建后的答案", null, "done", 3L, 7L, 5, 9,
                        java.time.LocalDateTime.of(2026, 10, 28, 9, 31), -1, "引用的版本已经过期",
                        List.of(new ChatDraftView(88L, "EVOLUTION_PROPOSAL", "自进化提案",
                                "{\"subKind\":\"PROMPT_TPL\"}", 1, null, null, null, false)), null, false)));

        List<ChatMessageView> got = controller.history(11L).getData();

        assertEquals(2, got.size());
        assertEquals("重建后的答案", got.get(1).content(),
                "core-rebuilt answer bodies must arrive unchanged — admin maps nothing");
        assertEquals(1, got.get(1).drafts().size(),
                "landed draft cards must ride the history bridge untouched — "
                        + "a reloaded page that loses them cannot confirm the proposal");
        assertEquals("EVOLUTION_PROPOSAL", got.get(1).drafts().get(0).objectType());
        verify(client).listHistory(11L, 7L);
    }

    @Test
    void historyLaneCarriesTheDialogDoorKey() throws NoSuchMethodException {
        Method lane = AiChatSessionController.class.getMethod("history", long.class);
        RequirePermission ann = lane.getAnnotation(RequirePermission.class);
        assertEquals("perm:aichat:use", ann == null ? null : ann.value(),
                "the history lane must require perm:aichat:use like its sibling doors");
    }

    // ---------- history list / rename / archive bridges ----------

    @Test
    void listAnchorsOnLoginIdentityAndIntersectsTheAskedSliceWithTheVisibleDomain() {
        login(7L);
        when(userProjects.listUserProjects(7L, false)).thenReturn(List.of(
                new UserProjectInfo(3L, "P3", "p3", 1, "成员", "zh"),
                new UserProjectInfo(4L, "P4", "p4", 2, "项目负责人", "zh")));
        when(client.listSessions(eq(7L), eq(List.of(3L)), any(), any(), any(), eq(1), eq(20)))
                .thenReturn(new ChatSessionPageMirrorView(List.of(), 0, 1, 20));

        // Project 99 is outside the visible domain — it must be dropped from
        // the segment before core is asked (forged/typo'd filters never widen).
        controller.list(List.of(3L, 99L), null, null, null, 1, 20);
        verify(client).listSessions(eq(7L), eq(List.of(3L)), any(), any(), any(), eq(1), eq(20));
    }

    @Test
    void listWithAnAllInvisibleSliceAnswersAnEmptyPageWithoutAskingCore() {
        login(7L);
        when(userProjects.listUserProjects(7L, false)).thenReturn(List.of(
                new UserProjectInfo(3L, "P3", "p3", 1, "成员", "zh")));

        ChatSessionPageMirrorView page = controller.list(List.of(99L), null, null, null, 2, 20).getData();

        assertEquals(0, page.total(), "an intersected-empty slice is an honest empty page");
        verifyNoInteractions(client);
    }

    @Test
    void renameAndArchiveInjectLoginIdentityOnly() {
        login(7L);
        when(client.renameSession(11L, 7L, "改后的标题")).thenReturn(session(11L, 3L, 7L));
        when(client.archiveSession(11L, 7L)).thenReturn(session(11L, 3L, 7L));

        controller.rename(11L, new SessionRenameRequest("改后的标题"));
        controller.archive(11L);

        verify(client).renameSession(11L, 7L, "改后的标题");
        verify(client).archiveSession(11L, 7L);
    }

    @Test
    void managementLanesCarryTheDialogDoorKey() throws NoSuchMethodException {
        for (Method lane : List.of(
                AiChatSessionController.class.getMethod("list", List.class, Integer.class,
                        Integer.class, String.class, int.class, int.class),
                AiChatSessionController.class.getMethod("rename", long.class, SessionRenameRequest.class),
                AiChatSessionController.class.getMethod("archive", long.class))) {
            RequirePermission ann = lane.getAnnotation(RequirePermission.class);
            assertEquals("perm:aichat:use", ann == null ? null : ann.value(),
                    lane.getName() + " must require perm:aichat:use like its sibling doors");
        }
    }

    // ---------- undo bridge ----------

    /**
     * The undo lane pins the operator from the login context only (a body
     * field can never speak for the user) and mirrors the drafted card id
     * back — ownership and every undo semantic judgment stay core-side, so
     * the bridge carries identity across and judges nothing.
     */
    @Test
    void undoInjectsLoginIdentityAndMirrorsTheDraftNumber() {
        login(7L);
        when(client.undoSession(11L, 7L)).thenReturn(777L);

        assertEquals(777L, controller.undo(11L).getData(),
                "the new pending card id must ride back to the browser unchanged");
        verify(client).undoSession(11L, 7L);
    }

    /** The undo lane shares the same catalog door key as its sibling doors. */
    @Test
    void undoLaneCarriesTheDialogDoorKey() throws NoSuchMethodException {
        Method lane = AiChatSessionController.class.getMethod("undo", long.class);
        RequirePermission ann = lane.getAnnotation(RequirePermission.class);
        assertEquals("perm:aichat:use", ann == null ? null : ann.value(),
                "the undo lane must require perm:aichat:use like its sibling doors");
    }
}

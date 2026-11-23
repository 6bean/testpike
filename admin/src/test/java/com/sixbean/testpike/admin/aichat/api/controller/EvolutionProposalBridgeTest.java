package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView;
import com.sixbean.testpike.admin.aichat.app.service.impl.EvolutionProposalAdminServiceImpl;
import com.sixbean.testpike.admin.aichat.app.service.impl.EvolutionSignalsAdminServiceImpl;
import com.sixbean.testpike.admin.aichat.app.service.impl.EvolutionTrendAdminServiceImpl;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Admin bridge contract for the manual self-evolution proposal run (AF-i
 * phase 2b-ii close-out). Asserted faces:
 * ①permission posture = the observation door's spectrum reused, not a new
 * one: only a project's manager (or ADMIN through the same guard) may fire a
 * run for that segment, and a refusal never reaches core — the run WRITES
 * pending draft cards, so an unauthorised trigger must leave zero rows;
 * ②the operator id is pinned from the login context, never from the request
 * body or query (horizontal line: the evolution session belongs to the human
 * who pressed the button);
 * ③a missing project is refused at the door with a readable sentence that
 * names what to pick — the core lane for a null segment does not exist, so the
 * bridge must not forward it and let core guess;
 * ④the core view deserialises by field name into the admin mirror record —
 * a dropped nested proposal row would hide a confirmable card from the UI.
 * <p>CoreAiChatClient is the integration seam faked at L2 per the bridge-test
 * precedent; the live admin→core chain is proven by L3 in this same package.
 */
class EvolutionProposalBridgeTest {

    private final CoreAiChatClient coreClient = mock(CoreAiChatClient.class);
    private final StubCoreProjectClient stub = new StubCoreProjectClient();
    private final AiChatEvolutionController controller = new AiChatEvolutionController(
            new EvolutionSignalsAdminServiceImpl(coreClient, new ProjectAccessGuard(stub)),
            new EvolutionProposalAdminServiceImpl(coreClient, new ProjectAccessGuard(stub)),
            new EvolutionTrendAdminServiceImpl(coreClient, new ProjectAccessGuard(stub)));

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(long userId, String... roles) {
        CurrentUser user = new CurrentUser(userId, "u" + userId, "U", "D", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }

    private static EvolutionProposalsMirrorView runFixture() {
        return new EvolutionProposalsMirrorView("2026-10", 3L, 55L, List.of(
                new EvolutionProposalsMirrorView.ProposalMirrorRow(
                        91L, "PROMPT_TPL", "dialog-answer-prompt", "提示词模板修订提案")));
    }

    @Test
    void plainMemberCannotFireARun_andCoreStaysUntouched() {
        login(7L, "TESTER");
        stub.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.generateProposals("2026-10", 3L));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(coreClient);
    }

    @Test
    void managerFiresWithLoginPinnedOperator_andNestedRowsSurviveTheBridge() {
        login(7L, "TEST_LEAD");
        stub.memberRoleResult(2);
        when(coreClient.generateEvolutionProposals(eq("2026-10"), eq(3L), anyLong()))
                .thenReturn(runFixture());
        Response<EvolutionProposalsMirrorView> resp = controller.generateProposals("2026-10", 3L);
        assertEquals("0", resp.getCode());
        assertEquals(55L, resp.getData().sessionId());
        assertEquals(1, resp.getData().proposals().size(),
                "proposal rows must survive the cross-service name mapping");
        assertEquals(91L, resp.getData().proposals().get(0).draftId());
        // operator pinned from the login context, not a request-supplied id
        verify(coreClient).generateEvolutionProposals("2026-10", 3L, 7L);
    }

    @Test
    void missingProjectIsRefusedAtTheDoor_withAReadableSentence() {
        login(7L, "ADMIN");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.generateProposals("2026-10", null));
        assertEquals(40001, ex.getCode());
        assertEquals("自进化提案需要选定项目：请先在顶栏选择项目，或请求里带上 projectId",
                ex.getMessage());
        verifyNoInteractions(coreClient);
    }

    // ---- rollback door (AF-i phase 3 close-out) ----

    private static com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView
            rollbackFixture() {
        return new com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView(
                88L, "dialog-answer-prompt", "对话", 3, 1, 7L, 87L, "2026-10-30 05:00:00");
    }

    @Test
    void rollbackPinsOperatorFromLogin_andReceiptFieldsCrossTheBridge() {
        login(7L, "TEST_LEAD");
        when(coreClient.rollbackEvolutionProposal(91L, 7L)).thenReturn(rollbackFixture());
        Response<com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView> resp =
                controller.rollbackProposal(91L);
        assertEquals("0", resp.getCode());
        // the copied-forward version and its source pointer must both arrive
        // — rollbackTo is the proof the prompt version chain was reused
        assertEquals(88L, resp.getData().id());
        assertEquals(3, resp.getData().version());
        assertEquals(87L, resp.getData().rollbackTo());
        verify(coreClient).rollbackEvolutionProposal(91L, 7L);
    }

    @Test
    void rollbackRefusalTravelsVerbatim_withZeroBridgeRewriting() {
        login(7L, "TESTER");
        when(coreClient.rollbackEvolutionProposal(91L, 7L)).thenThrow(
                new ServiceException("该提案尚未确认生效，没有可回滚的变更（先确认或在对话里作废）",
                        com.sixbean.testpike.common.exception.BizErrorCode.RESOURCE_CONFLICT));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.rollbackProposal(91L));
        assertEquals(40904, ex.getCode());
        assertEquals("该提案尚未确认生效，没有可回滚的变更（先确认或在对话里作废）", ex.getMessage());
    }

    @Test
    void rollbackOfAForeignCard_isRefusedCoreSide_andTheDoorAddsNoJudgment() {
        login(7L, "TESTER");
        when(coreClient.rollbackEvolutionProposal(91L, 7L)).thenThrow(
                new ServiceException("无权操作该会话",
                        com.sixbean.testpike.common.exception.BizErrorCode.FORBIDDEN));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.rollbackProposal(91L));
        // horizontal line: ownership is single-sourced in the core session
        // door (same posture as the draft-apply door) — the bridge only pins
        // the login identity into the operator slot, nothing else
        assertEquals(40301, ex.getCode());
        verify(coreClient).rollbackEvolutionProposal(91L, 7L);
    }
}

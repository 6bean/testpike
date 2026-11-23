package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Admin bridge contract for the evolution trend report (AF-i phase 4).
 * Asserted faces: ①permission spectrum = the SAME stance the proposal-run
 * door uses (a missing segment is refused 40001 with a sentence naming what to
 * pick, a named segment is its manager; a plain member's refusal never reaches
 * core); ②the query crosses untouched — month shape, window completeness and
 * the ineffective mark all stay single-sourced in core, the bridge judges
 * nothing; ③nested card rows deserialise by field name into the admin mirror
 * record (a dropped column would gut the report table downstream).
 * <p>CoreAiChatClient is the integration seam faked at L2 per the bridge-test
 * precedent; the live admin→core chain is proven by L3 at stage convergence.
 */
class EvolutionTrendBridgeTest {

    private final CoreAiChatClient coreClient = mock(CoreAiChatClient.class);
    private final StubCoreProjectClient stub = new StubCoreProjectClient();
    private final AiChatEvolutionController controller = new AiChatEvolutionController(
            new EvolutionSignalsAdminServiceImpl(coreClient, new ProjectAccessGuard(stub)),
            new com.sixbean.testpike.admin.aichat.app.service.impl.EvolutionProposalAdminServiceImpl(
                    coreClient, new ProjectAccessGuard(stub)),
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

    private static EvolutionTrendMirrorView reportFixture() {
        return new EvolutionTrendMirrorView("2026-07", 3L, "2026-08", true,
                List.of(new EvolutionTrendMirrorView.CardMirrorRow(
                        88L, "dialog-answer-prompt", "ineffective",
                        0.25, 0.24, 3, 3)));
    }

    @Test
    void missingSliceIsRefusedWithPickSentence_andCoreNeverTouched() {
        login(7L, "ADMIN");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.trend("2026-07", null));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("projectId"),
                "the refusal names what to pick, same sentence family as the run door");
        verifyNoInteractions(coreClient);
    }

    @Test
    void managerSliceQueryPassesThroughWithNestedRows() {
        login(7L, "TEST_LEAD");
        stub.memberRoleResult(2);
        when(coreClient.evolutionTrend("2026-07", 3L)).thenReturn(reportFixture());
        Response<EvolutionTrendMirrorView> resp = controller.trend("2026-07", 3L);
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().cards().size(),
                "nested verdict rows must survive the cross-service name mapping");
        assertEquals("ineffective", resp.getData().cards().get(0).verdict());
        verify(coreClient).evolutionTrend("2026-07", 3L);
    }

    @Test
    void plainMemberSliceRefused_beforeCore() {
        login(7L, "TESTER");
        stub.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.trend("2026-07", 3L));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(coreClient);
        verify(coreClient, never()).evolutionTrend(anyString(), anyLong());
    }
}

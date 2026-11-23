package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView;
import com.sixbean.testpike.admin.aichat.app.service.impl.EvolutionProposalAdminServiceImpl;
import com.sixbean.testpike.admin.aichat.app.service.impl.EvolutionSignalsAdminServiceImpl;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Admin bridge contract for the self-evolution observation rollup (AF-i
 * phase 2b). Asserted faces: ①permission spectrum = the SAME posture the cost
 * overview door uses (global rollup is ADMIN-only, a named project segment is
 * its manager) — no new judgment spectrum, and a refusal never reaches core;
 * ②the query crosses the boundary untouched (month shape and segment semantics
 * are validated fail-closed by core alone, the bridge judges nothing);
 * ③the core view deserialises by field name into the admin mirror record —
 * a silently dropped nested row would gut the tool-failure table downstream.
 * <p>CoreAiChatClient is the integration seam faked at L2 per the bridge-test
 * precedent; the live admin→core chain is proven by L3 at stage convergence.
 */
class EvolutionSignalBridgeTest {

    private final CoreAiChatClient coreClient = mock(CoreAiChatClient.class);
    private final StubCoreProjectClient stub = new StubCoreProjectClient();
    private final AiChatEvolutionController controller = new AiChatEvolutionController(
            new EvolutionSignalsAdminServiceImpl(coreClient, new ProjectAccessGuard(stub)),
            new EvolutionProposalAdminServiceImpl(coreClient, new ProjectAccessGuard(stub)),
            new com.sixbean.testpike.admin.aichat.app.service.impl.EvolutionTrendAdminServiceImpl(
                    coreClient, new ProjectAccessGuard(stub)));

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

    private static EvolutionSignalsMirrorView rollupFixture() {
        return new EvolutionSignalsMirrorView("2026-10", null, 40, 10, 0.25,
                7, 3, 2, List.of(new EvolutionSignalsMirrorView.ToolFailureMirrorRow(
                        "case.search", 5, "provider timeout")));
    }

    @Test
    void globalRollupIsAdminOnly_andRefusalNeverTouchesCore() {
        login(7L, "TESTER");
        stub.memberRoleResult(2); // even a top project role must not read the global rollup
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.evolutionSignals("2026-10", null));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(coreClient);
    }

    @Test
    void adminGlobalQueryPassesNullSliceThrough() {
        login(7L, "ADMIN");
        when(coreClient.evolutionSignals(eq("2026-10"), isNull())).thenReturn(rollupFixture());
        Response<EvolutionSignalsMirrorView> resp = controller.evolutionSignals("2026-10", null);
        assertEquals("0", resp.getCode());
        assertEquals(0.25, resp.getData().followUpRate(), 1e-9);
        assertEquals(1, resp.getData().toolFailures().size(),
                "nested failure rows must survive the cross-service name mapping");
        verify(coreClient).evolutionSignals("2026-10", null);
    }

    @Test
    void projectSliceRequiresManager_plainMemberRefused() {
        login(7L, "TESTER");
        stub.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.evolutionSignals("2026-10", 3L));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(coreClient);

        stub.memberRoleResult(2);
        when(coreClient.evolutionSignals("2026-10", 3L)).thenReturn(rollupFixture());
        assertEquals("0", controller.evolutionSignals("2026-10", 3L).getCode());
        verify(coreClient).evolutionSignals("2026-10", 3L);
    }
}

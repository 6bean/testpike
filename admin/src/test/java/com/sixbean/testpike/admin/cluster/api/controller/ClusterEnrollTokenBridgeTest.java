package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollIssueBody;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenIssuedView;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreClusterEnrollTokenClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contracts of the admin enroll-token bridge (ExecutorPoolFleetBridgeTest precedent;
 * client stub = L2 bridge-contract isolation, the real admin->core chain rides the
 * L3 live window of the wizard closing pack).
 * <p>Asserted surface: 1) the session primary role rides the port call while the body
 * carries no actorRole field at all, so a smuggled role has no channel; 2) the one-time
 * plaintext crosses exactly once (issued view keeps it, ledger view type has no token
 * field); 3) ledger fields project unchanged including the fingerprint binding;
 * 4) a core refusal (allow-roles whitelist, revoked-state) surfaces untouched.
 */
class ClusterEnrollTokenBridgeTest {

    private final CoreClusterEnrollTokenClient client = mock(CoreClusterEnrollTokenClient.class);
    private final ClusterEnrollTokenController controller = new ClusterEnrollTokenController(client);

    @AfterEach
    void clearSession() {
        UserContext.clear();
    }

    @Test
    void issue_injects_session_primary_role_and_forwards_body_fields_unchanged() {
        UserContext.set(new CurrentUser(7L, "ops", "Ops", null, List.of("admin", "tester"),
                System.currentTimeMillis() + 60_000L, null));
        FleetEnrollIssueBody body = new FleetEnrollIssueBody("executor", null, 1, "kitchen pilot");
        when(client.issue(any(), eq("admin"))).thenReturn(new FleetEnrollTokenIssuedView(
                42L, "tok-plaintext-once", "executor", null, 1, LocalDateTime.of(2026, 11, 22, 9, 0)));

        FleetEnrollTokenIssuedView got = controller.issue(body).getData();

        verify(client).issue(body, "admin");
        assertEquals(42L, got.id());
        assertEquals("tok-plaintext-once", got.token());
        // The request DTO has no actorRole component: the smuggling channel does not exist.
        assertEquals(4, FleetEnrollIssueBody.class.getRecordComponents().length);
    }

    @Test
    void issue_with_roleless_session_forwards_null_actor_and_lets_core_decide() {
        UserContext.set(new CurrentUser(7L, "ops", "Ops", null, List.of(),
                System.currentTimeMillis() + 60_000L, null));
        assertNull(controller.sessionRoleForTest(), "empty session yields no primary role");
    }

    @Test
    void list_projects_ledger_fields_including_fingerprint_binding() {
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 23, 1, 0);
        when(client.list()).thenReturn(List.of(new FleetEnrollTokenView(
                9L, "executor", "proj-a", 3, 1, 0, "fp-1234", expiry, "note", expiry, expiry)));

        FleetEnrollTokenView row = controller.list().getData().get(0);

        assertEquals(9L, row.id());
        assertEquals("proj-a", row.projectScope());
        assertEquals(3, row.maxUses());
        assertEquals(1, row.usedCount());
        assertEquals(0, row.status());
        assertEquals("fp-1234", row.boundFingerprint());
        // 5) the ledger view type is structurally plaintext-free: no token component exists.
        for (var c : FleetEnrollTokenView.class.getRecordComponents()) {
            if ("token".equals(c.getName())) {
                throw new AssertionError("ledger view must not carry a token field");
            }
        }
    }

    @Test
    void core_refusal_surfaces_untouched_and_revoke_forwards_id() {
        when(client.issue(any(), any())).thenThrow(new ServiceException(
                "当前角色不在签发白名单（installkit.allow-roles）"));
        UserContext.set(new CurrentUser(7L, "ops", "Ops", null, List.of("tester"),
                System.currentTimeMillis() + 60_000L, null));

        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.issue(new FleetEnrollIssueBody("executor", null, null, null)));
        assertEquals("当前角色不在签发白名单（installkit.allow-roles）", thrown.getMessage());

        controller.revoke(5L);
        verify(client).revoke(5L);
    }
}

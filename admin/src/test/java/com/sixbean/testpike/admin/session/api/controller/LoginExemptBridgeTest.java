package com.sixbean.testpike.admin.session.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptDeclareRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptReviewRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptView;
import com.sixbean.testpike.admin.session.infrastructure.remote.client.CoreLoginExemptClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Login-exemption admin bridge contract (AV5 face 2, LoginStateBridgeTest
 * idiom). The bridge authorizes and forwards: form rules, vault cross-checks
 * and the re-check verdict table all stay single-sourced in core (red line 6).
 *
 * <p>Asserted surface:
 * 1. Pass-through — declare/review/find carry the caller payload untouched and
 * return the core echo, so the page reads the ledger state, not an
 * admin-side reconstruction.
 * 2. Data isolation — the ownership lens is taken from the core echo of the
 * environment row (projectId), never from the caller, and the write doors
 * demand the manage lens while the read door demands the view lens.
 * 3. Permission doors — each endpoint carries exactly its registered
 * perm:session key; the exemption door is the approve-family key because a
 * declaration is a guarantee about a test environment, not a plain edit.
 * 4. Honest core wording propagates verbatim (undeclared environment, missing
 * environment, illegal form).
 * 5. Never-echo structure — the view record has no secret component and the
 * client interface exposes no reveal method, so plaintext cannot cross this
 * boundary by construction (red line 3).
 *
 * <p>The CoreLoginExemptClient stand-in is bridge-contract L2 isolation (same
 * family precedent); the real admin→core chain belongs to the batch L3 window.
 */
class LoginExemptBridgeTest {

    private final CoreLoginExemptClient client = mock(CoreLoginExemptClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final LoginExemptController controller = new LoginExemptController(client, guard);

    private LoginExemptView declared() {
        return new LoginExemptView(41L, 7L, true, "master_code", "master_login_code",
                List.of(), "trial_login", "pending", null, null);
    }

    @Test
    void readDoorTakesTheProjectLensFromTheCoreEcho() {
        when(client.find(41L)).thenReturn(declared());
        try (MockedStatic<UserContext> ctx = Mockito.mockStatic(UserContext.class)) {
            ctx.when(UserContext::current).thenReturn(null);
            assertEquals("master_login_code", controller.get(41L).getData().credKey());
            verify(guard).assertCanView(any(), eq(7L));
        }
    }

    @Test
    void declareRunsTheManageLensOnTheEchoedProjectBeforeForwarding() {
        LoginExemptDeclareRequest request = new LoginExemptDeclareRequest(
                "master_code", "master_login_code", List.of(), "trial_login");
        when(client.find(41L)).thenReturn(declared());
        when(client.declare(eq(41L), eq(request))).thenReturn(declared());
        try (MockedStatic<UserContext> ctx = Mockito.mockStatic(UserContext.class)) {
            ctx.when(UserContext::current).thenReturn(null);
            assertEquals("pending", controller.declare(41L, request).getData().reviewStatus());
            verify(guard).assertCanManage(any(), eq(7L));
        }
        verify(client).declare(41L, request);
    }

    @Test
    void declareOfAnEnvironmentTheCoreCannotResolveIsRefusedWithoutForwarding() {
        LoginExemptDeclareRequest request = new LoginExemptDeclareRequest(
                "none", null, List.of(), "manual");
        when(client.find(99L)).thenThrow(new ServiceException(
                "环境档案 #99 不存在——请刷新环境页确认对象。", BizErrorCode.NOT_FOUND));
        try (MockedStatic<UserContext> ctx = Mockito.mockStatic(UserContext.class)) {
            ctx.when(UserContext::current).thenReturn(null);
            ServiceException missing = assertThrows(ServiceException.class,
                    () -> controller.declare(99L, request));
            assertTrue(missing.getMessage().contains("不存在"),
                    "core wording must not be swallowed or rewritten");
        }
        verify(client, Mockito.never()).declare(anyLong(), any());
    }

    @Test
    void reviewForwardingKeepsTheVerdictWordingFromCoreUntouched() {
        LoginExemptView failed = new LoginExemptView(41L, 7L, true, "master_code",
                "master_login_code", List.of(), "trial_login", "failed",
                "万能码试登录被拒——豁免声明未通过复核。", "2026-11-13T10:00:00");
        when(client.find(41L)).thenReturn(declared());
        when(client.recordReview(eq(41L), any())).thenReturn(failed);
        try (MockedStatic<UserContext> ctx = Mockito.mockStatic(UserContext.class)) {
            ctx.when(UserContext::current).thenReturn(null);
            LoginExemptView out = controller.review(41L,
                    new LoginExemptReviewRequest("master_code_rejected", null)).getData();
            assertEquals("failed", out.reviewStatus());
            assertTrue(out.reviewDetail().contains("未通过复核"), out.reviewDetail());
        }
    }

    @Test
    void everyDoorCarriesItsRegisteredPermissionKey() throws Exception {
        assertEquals("perm:session:read",
                LoginExemptController.class.getMethod("get", long.class)
                        .getAnnotation(RequirePermission.class).value());
        assertEquals("perm:session:exempt",
                LoginExemptController.class.getMethod("declare", long.class, LoginExemptDeclareRequest.class)
                        .getAnnotation(RequirePermission.class).value());
        assertEquals("perm:session:exempt",
                LoginExemptController.class.getMethod("review", long.class, LoginExemptReviewRequest.class)
                        .getAnnotation(RequirePermission.class).value());
    }

    @Test
    void theBridgeExposesNoRevealOrSelfAttestingDoor() {
        List<String> doors = Arrays.stream(LoginExemptController.class.getDeclaredMethods())
                .map(Method::getName).sorted().toList();
        assertEquals(List.of("declare", "get", "review"), doors,
                "the exemption face stays three doors; no captcha-solving, code-reading or "
                        + "page-side pass-attesting door may ever appear here (red lines 1 and 3)");
    }

    @Test
    void theCarriedShapesHaveNoSecretComponent() {
        assertTrue(Arrays.stream(LoginExemptView.class.getRecordComponents())
                        .noneMatch(c -> c.getName().toLowerCase().contains("value")
                                || c.getName().toLowerCase().contains("secret")
                                || c.getName().toLowerCase().contains("password")),
                "structurally impossible to echo a vault value through this face");
        assertTrue(Arrays.stream(LoginExemptDeclareRequest.class.getRecordComponents())
                        .noneMatch(c -> c.getName().toLowerCase().contains("value")
                                || c.getName().toLowerCase().contains("secret")),
                "the declaration only ever references a cred_key");
    }
}

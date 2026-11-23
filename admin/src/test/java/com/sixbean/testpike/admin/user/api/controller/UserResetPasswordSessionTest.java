package com.sixbean.testpike.admin.user.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.SessionTokenService;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.admin.user.api.dto.ResetPasswordRequest;
import com.sixbean.testpike.admin.user.api.dto.UserView;
import com.sixbean.testpike.admin.user.infrastructure.remote.client.CoreUserClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class UserResetPasswordSessionTest {

    private CoreUserClient coreUsers;
    private SessionTokenService sessionTokens;
    private com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService passwordPolicy;
    private UserController controller;

    @BeforeEach
    void setUp() {
        coreUsers = mock(CoreUserClient.class);
        sessionTokens = mock(SessionTokenService.class);
        passwordPolicy = mock(com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService.class);
        controller = new UserController(coreUsers, mock(CoreRoleClient.class), sessionTokens, passwordPolicy);
        UserContext.set(new CurrentUser(1L, "admin", "管理员", null,
                List.of("ADMIN"), System.currentTimeMillis() + 3_600_000, null));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

 
    @Test
    void resetPassword_success_revokesAllSessions() {
        long target = 55L;
        when(coreUsers.resetPassword(anyLong(), anyString())).thenReturn(view(target));

        controller.resetPassword(target, new ResetPasswordRequest("NewPass@98765"));

        verify(coreUsers).resetPassword(target, "NewPass@98765");
        verify(sessionTokens).revokeAllSessions(target);
    }

 
    @Test
    void resetPassword_success_evictsExpiryCache() {
        long target = 56L;
        when(coreUsers.resetPassword(anyLong(), anyString())).thenReturn(view(target));

        controller.resetPassword(target, new ResetPasswordRequest("NewPass@98765"));

        verify(passwordPolicy).evict(target);
    }

 
    @Test
    void resetPassword_coreFails_noRevoke() {
        when(coreUsers.resetPassword(anyLong(), anyString()))
                .thenThrow(new ServiceException("用户不存在", 40401));

        assertThrows(ServiceException.class,
                () -> controller.resetPassword(99L, new ResetPasswordRequest("NewPass@98765")));

        verify(sessionTokens, never()).revokeAllSessions(anyLong());
        verify(passwordPolicy, never()).evict(anyLong());
    }

    
    @Test
    void resetPassword_self_rejected_noRevoke() {
        assertThrows(ServiceException.class,
                () -> controller.resetPassword(1L, new ResetPasswordRequest("NewPass@98765")));
        verify(sessionTokens, never()).revokeAllSessions(anyLong());
        verify(passwordPolicy, never()).evict(anyLong());
    }

    private static UserView view(long id) {
        return new UserView(id, "u" + id, "用户" + id, null, null, null,
                1, 1, 0, null, LocalDateTime.now(), LocalDateTime.now(), 0, null, null);
    }
}

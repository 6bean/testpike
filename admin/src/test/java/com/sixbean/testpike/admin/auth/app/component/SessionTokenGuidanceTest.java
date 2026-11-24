package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R63 refusal-guidance contract for the session-token door.
 *
 * <p>Every user-facing refusal raised by {@link SessionTokenService} must
 * name the concrete failure (expected token length, grace window, which
 * operation broke) and carry a repair entry the human can actually follow
 * on screen: the literal 「退出登录」 avatar-menu item or the 「登录页」 the
 * router guard redirects to. The static gate
 * (tools/error_guidance_coverage_gate.py) scans the sentence literals;
 * this class pins the same contract through the real component so a future
 * rewrite cannot silently drop a pointer again.
 *
 * <p>Redis is the only collaborator here and it is infrastructure, isolated
 * with the same harness as the sibling {@code SessionTokenServiceTest}; the
 * product code under test stays real. Refresh tokens are secrets, so every
 * refusal must also prove the raw token never echoes onto the screen.
 */
class SessionTokenGuidanceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private SessionTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        service = new SessionTokenService(redis);
    }

    private static void assertGuidedRefusal(ServiceException e, String causeFragment) {
        String msg = e.getMessage();
        assertTrue(msg.contains("「"), "refusal carries a bracketed literal entry: " + msg);
        assertTrue(msg.contains("请"), "refusal carries an action word: " + msg);
        assertTrue(msg.contains(causeFragment),
                "refusal names the concrete failure (" + causeFragment + "): " + msg);
    }

    @Test
    void logoutRevocationFailurePointsAtTheLogoutMenuItem() {
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.revokeJti("jti-abc", 900));
        assertGuidedRefusal(e, "登出");
        assertTrue(e.getMessage().contains("「退出登录」"),
                "retry entry is the literal avatar-menu item: " + e.getMessage());
    }

    @Test
    void sessionIssueFailurePointsAtReLoginPath() {
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.issueRefresh(42L, 7));
        assertGuidedRefusal(e, "建立会话");
        assertTrue(e.getMessage().contains("「登录页」"),
                "repair entry is the login page: " + e.getMessage());
    }

    @Test
    void illegalRefreshTokenQuotesExpectedLengthAndLoginPage() {
        String bad = "short-token";
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.rotateRefresh(bad, 7));
        assertGuidedRefusal(e, "43");
        assertTrue(e.getMessage().contains(String.valueOf(bad.length())),
                "refusal quotes the offending length: " + e.getMessage());
        assertEquals(40101, e.getCode());
        verify(valueOps, never()).getAndDelete(anyString());
    }

    @Test
    void rotationStorageFailurePointsAtReLoginPath() {
        String token = "t".repeat(43);
        when(valueOps.getAndDelete("auth:refresh:" + SessionTokenService.sha256Hex(token)))
                .thenThrow(new RuntimeException("connection refused"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.rotateRefresh(token, 7));
        assertGuidedRefusal(e, "续期");
        assertFalseTokenEchoed(e.getMessage(), token);
    }

    @Test
    void unknownRefreshTokenSaysExpiredAndNamesLoginPage() {
        String token = "m".repeat(43);
        when(valueOps.getAndDelete("auth:refresh:" + SessionTokenService.sha256Hex(token)))
                .thenReturn(null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.rotateRefresh(token, 7));
        assertGuidedRefusal(e, "过期");
        assertFalseTokenEchoed(e.getMessage(), token);
    }

    @Test
    void corruptedRefreshRecordNamesTheOwnershipFailure() {
        String token = "c".repeat(43);
        when(valueOps.getAndDelete("auth:refresh:" + SessionTokenService.sha256Hex(token)))
                .thenReturn("{}");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.rotateRefresh(token, 7));
        assertGuidedRefusal(e, "归属用户");
        assertFalseTokenEchoed(e.getMessage(), token);
    }

    @Test
    void replayWithinGraceWindowQuotesTheWindowMillis() {
        String token = "r".repeat(43);
        when(valueOps.getAndDelete("auth:refresh:" + SessionTokenService.sha256Hex(token)))
                .thenReturn("{\"userId\":42,\"consumedAt\":" + System.currentTimeMillis() + "}");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.rotateRefresh(token, 7));
        assertGuidedRefusal(e, "重复");
        assertTrue(e.getMessage().contains(String.valueOf(SessionTokenService.REPLAY_GRACE_MILLIS)),
                "grace window travels as a literal number: " + e.getMessage());
        assertFalseTokenEchoed(e.getMessage(), token);
    }

    @Test
    void revokeAllFailurePointsAtLogoutRetry() {
        when(setOps.members("auth:user-sessions:7"))
                .thenThrow(new RuntimeException("connection refused"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.revokeAllSessions(7L));
        assertGuidedRefusal(e, "登录会话");
        assertTrue(e.getMessage().contains("「退出登录」"),
                "retry entry is the literal avatar-menu item: " + e.getMessage());
        assertTrue(e.getMessage().contains("7"), "refusal quotes the account id: " + e.getMessage());
    }

    private static void assertFalseTokenEchoed(String message, String token) {
        assertTrue(!message.contains(token),
                "refusal never echoes the raw refresh token: " + message);
    }
}

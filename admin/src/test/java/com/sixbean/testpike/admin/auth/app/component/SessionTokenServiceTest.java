package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class SessionTokenServiceTest {

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


    @Test
    void revokeJti_writesBlacklistKeyWithRemainingTtl() {
        service.revokeJti("jti-123", 900);
        verify(valueOps).set(eq("auth:logout:jti-123"), eq("1"), eq(Duration.ofSeconds(900)));
    }

    @Test
    void revokeJti_alreadyExpired_noop() {
        service.revokeJti("jti-old", 0);
        service.revokeJti("jti-neg", -5);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void isJtiRevoked_trueWhenBlacklistExists() {
        when(valueOps.get("auth:logout:jti-abc")).thenReturn("1");
        assertTrue(service.isJtiRevoked("jti-abc"));
    }

    @Test
    void isJtiRevoked_falseWhenAbsentOrBlankJti() {
        when(valueOps.get("auth:logout:jti-none")).thenReturn(null);
        assertFalse(service.isJtiRevoked("jti-none"));
        assertFalse(service.isJtiRevoked(null));
        assertFalse(service.isJtiRevoked(""));
    }

    @Test
    void isJtiRevoked_redisDown_degradesToFalse() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        assertFalse(service.isJtiRevoked("jti-x"));
    }


    @Test
    void issueRefresh_returns256bitTokenAndStoresHashKey() {
        String token = service.issueRefresh(42L, 7);
        assertNotNull(token);
        assertEquals(43, token.length());
        String key = "auth:refresh:" + SessionTokenService.sha256Hex(token);
        verify(valueOps).set(eq(key), argThat(v -> v.contains("\"userId\":42")), eq(Duration.ofDays(7)));
        verify(setOps).add("auth:user-sessions:42", key);
        verify(redis).expire(eq("auth:user-sessions:42"), eq(Duration.ofDays(8)));
    }

    @Test
    void rotate_validToken_returnsUserIdAndOldKeyConsumed() {
        String token = "t".repeat(43);
        String key = "auth:refresh:" + SessionTokenService.sha256Hex(token);
        when(valueOps.getAndDelete(key)).thenReturn("{\"userId\":42,\"issuedAt\":1}");
        RotateOutcome outcome = service.rotateRefresh(token, 7);
        assertFalse(outcome.replayDetected());
        assertEquals(42L, outcome.userId());
        verify(setOps).remove(eq("auth:user-sessions:42"), eq(key));
    }

    @Test
    void rotate_replayWithinGraceWindow_401WithoutRevoke() {
        String token = "r".repeat(43);
        String key = "auth:refresh:" + SessionTokenService.sha256Hex(token);
        when(valueOps.getAndDelete(key))
                .thenReturn("{\"userId\":42,\"consumedAt\":" + System.currentTimeMillis() + "}");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.rotateRefresh(token, 7));
        assertEquals(40101, ex.getCode());
        verify(redis, never()).delete((List<String>) any());
        verify(setOps, never()).members(anyString());
    }

    @Test
    void rotate_replayBeyondGraceWindow_revokesAllSessionsOfUser() {
        String token = "r".repeat(43);
        String key = "auth:refresh:" + SessionTokenService.sha256Hex(token);
        long old = System.currentTimeMillis() - 60_000;
        when(valueOps.getAndDelete(key))
                .thenReturn("{\"userId\":42,\"consumedAt\":" + old + "}");
        when(setOps.members("auth:user-sessions:42"))
                .thenReturn(Set.of(key, "auth:refresh:other"));
        RotateOutcome outcome = service.rotateRefresh(token, 7);
        assertTrue(outcome.replayDetected());
        assertEquals(42L, outcome.userId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(redis).delete(captor.capture());
        assertTrue(captor.getValue().contains("auth:refresh:other"));
        verify(redis).delete("auth:user-sessions:42");
    }

    @Test
    void rotate_missingKey_throwsUnauthorized() {
        String token = "m".repeat(43);
        when(valueOps.getAndDelete("auth:refresh:" + SessionTokenService.sha256Hex(token)))
                .thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.rotateRefresh(token, 7));
        assertEquals(40101, ex.getCode());
    }

    @Test
    void rotate_illegalTokenFormat_401WithoutRedisTouch() {
        for (String bad : new String[]{"short", "", null, "!" + "x".repeat(50)}) {
            ServiceException ex = assertThrows(ServiceException.class, () -> service.rotateRefresh(bad, 7));
            assertEquals(40101, ex.getCode());
        }
        verify(valueOps, never()).getAndDelete(anyString());
    }


    @Test
    void revokeAllSessions_deletesKeysAndIndex() {
        when(setOps.members("auth:user-sessions:7"))
                .thenReturn(Set.of("auth:refresh:a", "auth:refresh:b"));
        service.revokeAllSessions(7L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(redis).delete(captor.capture());
        assertEquals(2, captor.getValue().size());
        verify(redis).delete("auth:user-sessions:7");
    }

    @Test
    void revokeAllSessions_emptyIndex_idempotent() {
        when(setOps.members("auth:user-sessions:8")).thenReturn(Set.of());
        service.revokeAllSessions(8L);
        verify(redis, never()).delete((List<String>) any());
        verify(redis).delete("auth:user-sessions:8");
    }

    @Test
    void revokeAllSessions_otherUserUntouched() {
        service.revokeAllSessions(9L);
        verify(setOps).members("auth:user-sessions:9");
        verify(redis).delete("auth:user-sessions:9");
    }


    @Test
    void deleteRefresh_removesKeyAndIndexMember() {
        String token = "d".repeat(43);
        String key = "auth:refresh:" + SessionTokenService.sha256Hex(token);
        when(valueOps.get(key)).thenReturn("{\"userId\":42,\"issuedAt\":1}");
        service.deleteRefresh(token);
        verify(redis).delete(key);
        verify(setOps).remove("auth:user-sessions:42", key);
    }

    @Test
    void deleteRefresh_illegalOrMissing_silent() {
        service.deleteRefresh(null);
        service.deleteRefresh("bad");
        when(valueOps.get("auth:refresh:" + SessionTokenService.sha256Hex("e".repeat(43))))
                .thenReturn(null);
        service.deleteRefresh("e".repeat(43));
        verify(redis, never()).delete(anyString());
    }


    @Test
    void issueRefresh_redisDown_throwsServiceUnavailable() {
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.issueRefresh(1L, 7));
        assertEquals(50301, ex.getCode());
    }
}

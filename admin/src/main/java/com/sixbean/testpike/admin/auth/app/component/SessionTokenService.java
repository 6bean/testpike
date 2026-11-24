package com.sixbean.testpike.admin.auth.app.component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * <ul>
 * </ul>
 *
 */
public final class SessionTokenService {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);

 
    public static final String LOGOUT_KEY_PREFIX = "auth:logout:";
    
    public static final String REFRESH_KEY_PREFIX = "auth:refresh:";
    
    public static final String USER_SESSIONS_KEY_PREFIX = "auth:user-sessions:";

 
    static final long REPLAY_GRACE_MILLIS = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public SessionTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }


    
    public void revokeJti(String jti, long remainingSeconds) {
        if (jti == null || jti.isBlank() || remainingSeconds <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(LOGOUT_KEY_PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds));
        } catch (RuntimeException e) {
            log.error("登出吊销失败（Redis 不可用）jti={}", jti, e);
            throw new ServiceException("系统繁忙，登出未完全生效——请稍后点击右上角头像菜单中的「退出登录」"
                    + "重新退出一次；本次会话将在访问令牌到期后自动失效", BizErrorCode.SERVICE_UNAVAILABLE);
        }
    }

 
    public boolean isJtiRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return redis.opsForValue().get(LOGOUT_KEY_PREFIX + jti) != null;
        } catch (RuntimeException e) {
            log.warn("黑名单查询降级（Redis 不可用，不误杀会话）jti={}", jti, e);
            return false;
        }
    }


    
    public String issueRefresh(long userId, long refreshTtlDays) {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        String key = REFRESH_KEY_PREFIX + sha256Hex(token);
        try {
            JSONObject value = new JSONObject();
            value.put("userId", userId);
            value.put("issuedAt", System.currentTimeMillis());
            redis.opsForValue().set(key, value.toJSONString(), Duration.ofDays(refreshTtlDays));
            String indexKey = USER_SESSIONS_KEY_PREFIX + userId;
            redis.opsForSet().add(indexKey, key);
            redis.expire(indexKey, Duration.ofDays(refreshTtlDays + 1));
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("refresh 签发失败（Redis 不可用）userId={}", userId, e);
            throw new ServiceException("系统繁忙，暂无法建立会话——请稍后点击右上角头像菜单中的「退出登录」，"
                    + "再到「登录页」重新登录", BizErrorCode.SERVICE_UNAVAILABLE);
        }
        return token;
    }

    
    public RotateOutcome rotateRefresh(String token, long refreshTtlDays) {
        if (!isLegalRefresh(token)) {
            throw new ServiceException("登录会话凭证无效（长度 "
                    + (token == null ? "空" : token.length()) + "，应为 43 位）"
                    + "——请在「登录页」重新登录后继续操作", BizErrorCode.UNAUTHORIZED);
        }
        String key = REFRESH_KEY_PREFIX + sha256Hex(token);
        String raw;
        try {
            raw = redis.opsForValue().getAndDelete(key);
        } catch (RuntimeException e) {
            log.error("refresh 轮换失败（Redis 不可用）", e);
            throw new ServiceException("系统繁忙，登录会话续期未完成——请稍后点击右上角头像菜单中的「退出登录」，"
                    + "再到「登录页」重新登录", BizErrorCode.SERVICE_UNAVAILABLE);
        }
        if (raw == null) {
            throw new ServiceException("登录会话已过期或已注销——请在「登录页」重新登录后继续操作",
                    BizErrorCode.UNAUTHORIZED);
        }
        JSONObject obj = parseQuietly(raw);
        long userId = obj == null ? 0 : obj.getLongValue("userId");
        Long consumedAt = obj == null ? null : obj.getLong("consumedAt");
        if (userId <= 0) {
            throw new ServiceException("登录会话记录异常（无法识别归属用户）——请在「登录页」重新登录",
                    BizErrorCode.UNAUTHORIZED);
        }
        if (consumedAt != null) {
            if (System.currentTimeMillis() - consumedAt <= REPLAY_GRACE_MILLIS) {
                throw new ServiceException("该登录凭证在 " + REPLAY_GRACE_MILLIS
                        + " 毫秒内被重复使用，本次续期已终止——请在「登录页」重新登录",
                        BizErrorCode.UNAUTHORIZED);
            }
            revokeAllSessions(userId);
            return new RotateOutcome(userId, true);
        }
        try {
            JSONObject marker = new JSONObject();
            marker.put("userId", userId);
            marker.put("consumedAt", System.currentTimeMillis());
            redis.opsForValue().set(key, marker.toJSONString(), Duration.ofDays(refreshTtlDays));
            redis.opsForSet().remove(USER_SESSIONS_KEY_PREFIX + userId, key);
        } catch (RuntimeException e) {
            log.warn("refresh 宽窗标记写入失败 userId={}", userId, e);
        }
        return new RotateOutcome(userId, false);
    }

    
    public void deleteRefresh(String token) {
        if (!isLegalRefresh(token)) {
            return;
        }
        String key = REFRESH_KEY_PREFIX + sha256Hex(token);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return;
            }
            redis.delete(key);
            JSONObject obj = parseQuietly(raw);
            if (obj != null && obj.getLongValue("userId") > 0) {
                redis.opsForSet().remove(USER_SESSIONS_KEY_PREFIX + obj.getLongValue("userId"), key);
            }
        } catch (RuntimeException e) {
            log.warn("refresh 删除失败（Redis 不可用，TTL 兜底自然过期）", e);
        }
    }

 
    public void revokeAllSessions(long userId) {
        String indexKey = USER_SESSIONS_KEY_PREFIX + userId;
        try {
            Set<String> keys = redis.opsForSet().members(indexKey);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(List.copyOf(keys));
            }
            redis.delete(indexKey);
        } catch (RuntimeException e) {
            log.error("全会话吊销失败（Redis 不可用）userId={}", userId, e);
            throw new ServiceException("系统繁忙，未能注销账号（用户号 " + userId
                    + "）的全部登录会话——请稍后点击右上角头像菜单中的「退出登录」重试",
                    BizErrorCode.SERVICE_UNAVAILABLE);
        }
    }

 
    private static boolean isLegalRefresh(String token) {
        if (token == null || token.length() != 43) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static JSONObject parseQuietly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

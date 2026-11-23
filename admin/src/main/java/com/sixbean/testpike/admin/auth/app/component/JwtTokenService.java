package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.api.dto.SsoUser;
import com.sixbean.testpike.common.exception.BizErrorCode;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sixbean.testpike.common.exception.ServiceException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;


public final class JwtTokenService {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final byte[] secret;
    private final long ttlSeconds;

    public JwtTokenService(String secret, long ttlSeconds) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("SSO_JWT_SECRET 未配置或过短（至少 32 字符，从 .env 读取）");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    
    public String issue(SsoUser user) {
        return issue(user, ttlSeconds);
    }

    
    public String issue(SsoUser user, long ttlOverride) {
        long now = System.currentTimeMillis() / 1000;
        long exp = now + ttlOverride;
        JSONObject payload = new JSONObject();
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("sub", user.userId());
        payload.put("name", user.username());
        payload.put("nick", user.nickName());
        payload.put("dept", user.deptName());
        payload.put("roles", user.roles());
        payload.put("avatar", user.avatar());
        payload.put("iat", now);
        payload.put("exp", exp);
        payload.put("pwdIat", user.passwordChangedAt());
        String signingInput = b64url(HEADER.getBytes(StandardCharsets.UTF_8))
                + "." + b64url(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64url(hmac(signingInput));
    }

    
    public long ttlSeconds() {
        return ttlSeconds;
    }

    
    public String jtiOf(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return JSON.parseObject(payloadJson).getString("jti");
        } catch (RuntimeException e) {
            return null;
        }
    }

    
    public CurrentUser parse(String token) {
        if (token == null || token.isBlank()) {
            throw new ServiceException("登录状态已失效，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new ServiceException("登录凭证格式错误，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("登录凭证已损坏，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(hmac(signingInput), actual)) {
            throw new ServiceException("登录凭证无效，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        String payloadJson;
        try {
            payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("登录凭证已损坏，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(payloadJson);
        } catch (Exception e) {
            throw new ServiceException("登录凭证已损坏，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        long exp = obj.getLongValue("exp");
        if (exp < System.currentTimeMillis() / 1000) {
            throw new ServiceException("登录已过期，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        List<String> roles = obj.getList("roles", String.class);
        return new CurrentUser(obj.getLongValue("sub"), obj.getString("name"),
                obj.getString("nick"), obj.getString("dept"),
                roles == null ? List.of() : roles, exp * 1000, obj.getString("avatar"),
                obj.getString("jti"), obj.getLong("pwdIat"));
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}

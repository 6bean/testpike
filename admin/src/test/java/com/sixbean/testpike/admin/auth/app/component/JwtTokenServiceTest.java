package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.api.dto.SsoUser;
import com.sixbean.testpike.admin.auth.app.component.JwtTokenService;


import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-for-jwt-test-only-0123456789abcdef";

    private final JwtTokenService service = new JwtTokenService(SECRET, 7200);

    private SsoUser user() {
 return new SsoUser(1L, "tester", "Tester", "测试部",
                List.of("ADMIN", "TEST_LEAD"), "tester@example.com", "https://avatar.example.com/tester.png");
    }

    @Test
    void issue_parse_签发解析闭环载荷完整() {
        String token = service.issue(user());
        CurrentUser parsed = service.parse(token);

        assertEquals(1L, parsed.userId());
        assertEquals("tester", parsed.username());
 assertEquals("Tester", parsed.nickName());
        assertEquals("测试部", parsed.deptName());
        assertEquals(List.of("ADMIN", "TEST_LEAD"), parsed.roles());
        assertEquals("https://avatar.example.com/tester.png", parsed.avatar(), "K5 头像随 JWT 载荷");
        assertTrue(parsed.expiresAt() > System.currentTimeMillis(), "过期时间必须在未来");
    }

    @Test
    void parse_过期token被拒绝() {
        JwtTokenService shortTtl = new JwtTokenService(SECRET, -1);
        String token = shortTtl.issue(user());
        assertThrows(Exception.class, () -> shortTtl.parse(token), "过期 token 必须拒绝");
    }

    @Test
    void parse_篡改载荷被检出() {
        String token = service.issue(user());
        String[] parts = token.split("\\.");
        String tamperedPayload = tamperBase64Url(parts[1]);
        String forged = parts[0] + "." + tamperedPayload + "." + parts[2];
        assertThrows(Exception.class, () -> service.parse(forged), "篡改载荷必须检出");
    }

    @Test
    void parse_错误密钥签名被拒绝() {
        String token = service.issue(user());
        JwtTokenService other = new JwtTokenService("another-secret-0123456789abcdefghijklmn", 7200);
        assertThrows(Exception.class, () -> other.parse(token), "不同密钥签发的 token 必须拒绝");
    }

    @Test
    void parse_非JWT格式被拒绝() {
        assertThrows(Exception.class, () -> service.parse("not-a-jwt"));
        assertThrows(Exception.class, () -> service.parse("a.b"));
        assertThrows(Exception.class, () -> service.parse(""));
    }

    @Test
    void issue_相同用户两次签发token不同() {
        String t1 = service.issue(user());
        String t2 = service.issue(user());
        assertTrue(!t1.equals(t2), "含 iat 的 JWT 每次签发必须不同");
    }

    
    private String tamperBase64Url(String payload) {
        char[] chars = payload.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == 'A') {
                chars[i] = 'B';
                return new String(chars);
            }
            if (c == 'a') {
                chars[i] = 'b';
                return new String(chars);
            }
        }
        return payload + "x";
    }
}

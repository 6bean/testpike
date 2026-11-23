package com.sixbean.testpike.admin.auth.app.component;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JwtAuthFilterStorageFileWhitelistTest {

    private JwtAuthFilter filter() {
        return new JwtAuthFilter(null, null, null, null);
    }

    @Test
    void 预签名文件下载与直传路径放行() {
        MockHttpServletRequest get = new MockHttpServletRequest("GET",
                "/storage/files/2/script/202609/ab12-a.txt");
        MockHttpServletRequest put = new MockHttpServletRequest("PUT",
                "/storage/files/5/artifact/202609/0011-b.zip");
        assertTrue(filter().shouldNotFilter(get), "带签下载 URL 无 JWT 消费方必须放行");
        assertTrue(filter().shouldNotFilter(put), "带签直传 PUT 必须放行");
    }

    @Test
    void 其余存储路径仍须JWT_放行不外溢() {
        assertFalse(filter().shouldNotFilter(new MockHttpServletRequest("GET", "/storage/config")),
                "配置端点不在放行范围");
        assertFalse(filter().shouldNotFilter(new MockHttpServletRequest("GET", "/storage/filesX")),
                "前缀匹配须带斜杠，/storage/filesX 不得误放行");
        assertFalse(filter().shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/users")));
    }
}

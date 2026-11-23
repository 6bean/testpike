package com.sixbean.testpike.admin.core.infrastructure.remote.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class HttpForwardHeadersTest {

    @Test
    void stripsHopByHopHeadersKeepsContentType() {
        HttpHeaders in = new HttpHeaders();
        in.add(HttpHeaders.TRANSFER_ENCODING, "chunked");
        in.add(HttpHeaders.CONTENT_LENGTH, "42");
        in.add(HttpHeaders.CONNECTION, "keep-alive");
        in.add(HttpHeaders.CONTENT_TYPE, "application/json");
        in.add("X-Biz-Header", "keep-me");

        HttpHeaders out = HttpForwardHeaders.strip(in);

        assertFalse(out.containsKey(HttpHeaders.TRANSFER_ENCODING), "Transfer-Encoding 必须剥除");
        assertFalse(out.containsKey(HttpHeaders.CONTENT_LENGTH), "Content-Length 必须剥除（重算归容器）");
        assertFalse(out.containsKey(HttpHeaders.CONNECTION), "Connection 必须剥除");
        assertTrue(out.containsKey(HttpHeaders.CONTENT_TYPE), "Content-Type 必须保留");
        assertEquals("keep-me", out.getFirst("X-Biz-Header"), "业务头必须保留");
    }

    @Test
    void caseInsensitiveStrip() {
        HttpHeaders in = new HttpHeaders();
        in.add("transfer-encoding", "chunked");
        HttpHeaders out = HttpForwardHeaders.strip(in);
        assertTrue(out.isEmpty(), "小写逐跳头同样剥除");
    }
}

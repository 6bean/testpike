package com.sixbean.testpike.admin.core.infrastructure.remote.client;

import org.springframework.http.HttpHeaders;

import java.util.Locale;
import java.util.Set;


public final class HttpForwardHeaders {

    
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade",
            "content-length", "host", "expect");

    private HttpForwardHeaders() {
    }

    
    public static HttpHeaders strip(HttpHeaders source) {
        HttpHeaders out = new HttpHeaders();
        source.forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                out.addAll(name, values);
            }
        });
        return out;
    }
}

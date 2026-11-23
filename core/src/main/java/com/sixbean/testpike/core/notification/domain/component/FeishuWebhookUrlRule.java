package com.sixbean.testpike.core.notification.domain.component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;


public final class FeishuWebhookUrlRule {

    
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    
    public static String ruleText() {
        return "仅允许 https 地址或本机回环（localhost/127.0.0.1）夹具地址";
    }

    
    public static boolean isHttpsOrLoopback(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return true;
        }
        return "http".equalsIgnoreCase(scheme)
                && uri.getHost() != null
                && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase());
    }

    private FeishuWebhookUrlRule() {
    }
}

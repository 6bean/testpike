package com.sixbean.testpike.core.executor.domain.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;


@Component
public class ProdBlacklistGuard {

    private final List<String> patterns;

    public ProdBlacklistGuard(@Value("${testpike.prod-blacklist:}") String blacklist) {
        this.patterns = parsePatterns(blacklist);
    }

    
    public List<String> patterns() {
        return List.copyOf(patterns);
    }

    
    public boolean matches(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || patterns.isEmpty()) {
            return false;
        }
        String host = extractHost(baseUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.toLowerCase();
        for (String pattern : patterns) {
            if (match(lower, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean match(String host, String pattern) {
        if (pattern.startsWith("*.")) {
            String domain = pattern.substring(2);
            return host.equals(domain) || host.endsWith("." + domain);
        }
        return host.equals(pattern);
    }

    
    public static boolean hostMatches(String host, String pattern) {
        return host != null && match(host.toLowerCase(), pattern.trim().toLowerCase());
    }

    
    public static String hostOf(String url) {
        return extractHost(url);
    }

    
    private static List<String> parsePatterns(String blacklist) {
        List<String> result = new ArrayList<>();
        if (blacklist == null || blacklist.isBlank()) {
            return result;
        }
        for (String raw : blacklist.split(",")) {
            String item = raw.trim().toLowerCase();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    
    private static String extractHost(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }
}

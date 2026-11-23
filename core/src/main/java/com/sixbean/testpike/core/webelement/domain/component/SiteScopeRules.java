package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Segment-aligned site scope rules for whole-site survey profiles.
 * <p>A scope is derived from the start URL on the server only, and containment
 * is decided per whole path segment: a /pr/ scope never swallows /print/, and
 * one host can carry many sites side by side (/pr/, /md/), each surveyed by
 * its own profile. Same-host links under a different top-level segment are
 * reported as sibling segments - they are counted for the operator, never
 * queued for collection.
 */
public final class SiteScopeRules {

    private SiteScopeRules() {
    }

    /**
     * Normalize a start URL into its segment-aligned scope ("/pr/" for
     * https://xx.com/pr, "/" for the whole host). Query and fragment are
     * dropped; only absolute http(s) URLs with a host are accepted.
     *
     * @throws ServiceException when the value is blank or not a usable http(s) URL
     */
    public static String deriveScope(String startUrl) {
        URI uri = requireHttp(startUrl);
        return toScope(segments(uri.getPath()));
    }

    /** Origin key (scheme://host:effectivePort, lower-cased) used for same-site comparison. */
    public static String originOf(String url) {
        try {
            return originOf(new URI(url.trim()));
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }

    private static String originOf(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(scheme);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    /**
     * True when the URL lives inside the profile: same origin as the profile's
     * start URL, and its path segments start with the scope's segments. The
     * root scope "/" covers the whole origin.
     */
    public static boolean inScope(String origin, String scopePrefix, String url) {
        String host = originOf(origin);
        String candidate = originOf(url);
        if (host == null || candidate == null || !host.equals(candidate)) {
            return false;
        }
        List<String> scope = segments(scopePrefix);
        if (scope.isEmpty()) {
            return true;
        }
        try {
            List<String> path = segments(new URI(url.trim()).getPath());
            if (path.size() < scope.size()) {
                return false;
            }
            for (int i = 0; i < scope.size(); i++) {
                if (!scope.get(i).equals(path.get(i))) {
                    return false;
                }
            }
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Name of the sibling top-level segment when the URL sits on the profile's
     * own host but outside its scope ("/md/doc/1" beside a /pr/ scope yields
     * "md"). Returns null when the link is in scope, when the scope is the
     * whole host, or when the link belongs to a foreign or unusable origin -
     * foreign links are counted as out-of-origin instead of per segment.
     */
    public static String siblingSegment(String origin, String scopePrefix, String url) {
        List<String> scope = segments(scopePrefix);
        if (scope.isEmpty() || inScope(origin, scopePrefix, url)) {
            return null;
        }
        String host = originOf(origin);
        String candidate = originOf(url);
        if (host == null || !host.equals(candidate)) {
            return null;
        }
        try {
            List<String> path = segments(new URI(url.trim()).getPath());
            return path.isEmpty() ? null : path.get(0);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static URI requireHttp(String startUrl) {
        if (startUrl == null || startUrl.isBlank()) {
            throw new ServiceException("起始地址不能为空，请输入完整网址，示例：https://xx.com/pr/",
                    BizErrorCode.VALIDATION_ERROR);
        }
        URI uri;
        try {
            uri = new URI(startUrl.trim());
        } catch (URISyntaxException e) {
            throw new ServiceException("起始地址「%s」不是可识别的网址，请检查拼写（如 https://xx.com/pr/）",
                    BizErrorCode.VALIDATION_ERROR, startUrl.trim());
        }
        if (originOf(uri) == null) {
            throw new ServiceException("起始地址「%s」不是可用的网址，请改成以 http:// 或 https:// 开头且带域名的完整写法，示例：https://xx.com/pr/",
                    BizErrorCode.VALIDATION_ERROR, startUrl.trim());
        }
        return uri;
    }

    /** Split a path into its non-empty segments (order preserved). */
    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    /** Rebuild a trailing-slash-aligned scope prefix from segments ("/" when empty). */
    private static String toScope(List<String> segments) {
        if (segments.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", segments) + "/";
    }
}

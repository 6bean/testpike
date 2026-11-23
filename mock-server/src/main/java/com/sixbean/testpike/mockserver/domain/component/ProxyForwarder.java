package com.sixbean.testpike.mockserver.domain.component;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Forwarding base of the proxy recording plane : when the
 * pinned snapshot carries an open cassette box and the space row carries the
 * gated proxy target, requests that NO stub matched ride this client to the
 * real dependency and come back answered instead of a bare 404.
 * <p>Security stance (SSRF defense, second hop): the upstream host is the
 * bound proxyTargetUrl — pinned on the space row AFTER the core allowlist
 * gate (mock.proxy.allowlist, — never the incoming request's Host.
 * The relative path is joined through the multi-argument URI constructor so
 * the already-encoded servlet path cannot escape into a new authority.
 * <p>Wire honesty: chunked upstream bodies are auto-decoded by the JDK
 * client; hop-by-hop headers are stripped and the container recomputes
 * framing on the way back out. 4xx/5xx from the dependency ride through
 * untouched (a real 404 through the proxy is not swallowed into the mock's
 * own not-matched verdict).
 * <p>Frames leave this class RAW; masking belongs to the recorder (single
 * PII gate at buffer time), so no masking rules live here.
 */
public class ProxyForwarder {

    /** Hop-by-hop and framing headers: never republished through the proxy. */
    private static final Set<String> DROPPED_RESPONSE_HEADERS =
            Set.of("transfer-encoding", "connection", "keep-alive", "proxy-authenticate",
                    "proxy-authorization", "te", "trailer", "upgrade", "content-length");
    /** Client headers that must not be replayed to the dependency verbatim. */
    private static final Set<String> DROPPED_REQUEST_HEADERS =
            Set.of("host", "content-length", "connection", "accept-encoding",
                    "proxy-authorization", "upgrade");

    private final HttpClient client;
    private final Duration readTimeout;

    public ProxyForwarder(Duration connectTimeout, Duration readTimeout) {
        this.readTimeout = readTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Forward one unmatched request to the bound target. Returns an answered
     * result for any upstream status, or a transport-failure result (null
     * status + plain-word error) when the dependency is unreachable — the
     * caller turns that into an honest 502, this class never guesses.
     *
     * @param relativePath request path below /mocks/{spaceKey}, already decoded
     *                     by the servlet container
     * @param rawQuery     the request's raw query string (kept as-is so encoded
     *                     values survive the hop unchanged)
     */
    public ProxyForwardResult forward(String method, String relativePath, String rawQuery,
                                      Map<String, String> requestHeaders, String body,
                                      String proxyTargetUrl) {
        long startedAt = System.currentTimeMillis();
        URI target = buildTargetUri(proxyTargetUrl, relativePath, rawQuery);
        if (target == null) {
            return new ProxyForwardResult(null, Map.of(), null,
                    "代理目标地址无法解析（space.proxyTargetUrl 值非法），拒绝转发",
                    System.currentTimeMillis() - startedAt);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(readTimeout);
            applyHeaders(builder, requestHeaders);
            builder.method(safeMethod(method),
                    body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> upstream = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new LinkedHashMap<>();
            upstream.headers().map().forEach((k, v) -> {
                if (!DROPPED_RESPONSE_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                    headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v));
                }
            });
            return new ProxyForwardResult(upstream.statusCode(), headers,
                    upstream.body(), null, System.currentTimeMillis() - startedAt);
        } catch (IOException ex) {
            return new ProxyForwardResult(null, Map.of(), null,
                    "真实依赖不可达：" + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : " " + ex.getMessage()),
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProxyForwardResult(null, Map.of(), null,
                    "转发被中断", System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * Build one RAW frame of a forwarded exchange for the open box. The path
     * facet keeps the relative path WITH query so core's endpoint cluster
     * normalizes ids while still seeing a faithful sample. Headers arrive
     * unmasked — the recorder's shared gate masks at buffer time.
     */
    public com.sixbean.testpike.common.mq.MockRecordingFrameMessage frameOf(
            HttpServletRequest request, String relativePath, String body,
            ProxyForwardResult result, String spaceKey, long recordingId) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        String query = request.getQueryString();
        String path = relativePath + (query == null || query.isBlank() ? "" : "?" + query);
        return new com.sixbean.testpike.common.mq.MockRecordingFrameMessage(
                java.util.UUID.randomUUID().toString(), request.getMethod(), path, headers, body,
                result.status(), result.headers(), result.body(),
                (int) result.latencyMs(), System.currentTimeMillis(), spaceKey, recordingId);
    }

    /** Join target + relative path; the multi-arg URI pins scheme and authority. */
    private static URI buildTargetUri(String proxyTargetUrl, String relativePath, String rawQuery) {
        try {
            URI base = URI.create(proxyTargetUrl);
            if (base.getHost() == null || base.getScheme() == null) {
                return null;
            }
            String basePath = base.getPath() == null ? "" : base.getPath();
            if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            String tail = relativePath == null || relativePath.isEmpty() ? "/" : relativePath;
            String merged = basePath + (tail.startsWith("/") ? tail : "/" + tail);
            // the incoming raw query wins; the target's own query (rare: a
            // version-pinned dependency path) only applies when the request has none
            String query = rawQuery == null || rawQuery.isBlank() ? base.getQuery() : rawQuery;
            return new URI(base.getScheme(), base.getAuthority(), merged, query, null);
        } catch (Exception ex) {
            return null;
        }
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> requestHeaders) {
        requestHeaders.forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            if (!DROPPED_REQUEST_HEADERS.contains(name) && v != null) {
                builder.header(k, v);
            }
        });
    }

    /** JDK client rejects CONNECT/TRACE — an odd verb simply rides as GET. */
    private static String safeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "GET";
        }
        String upper = method.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "CONNECT", "TRACE" -> "GET";
            default -> upper;
        };
    }
}

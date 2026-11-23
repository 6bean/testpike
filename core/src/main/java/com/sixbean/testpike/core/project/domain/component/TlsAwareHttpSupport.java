package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.common.util.TlsMode;
import com.sixbean.testpike.common.util.TlsPolicy;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * <ul>
 * </ul>
 */
@Component
public class TlsAwareHttpSupport {

    private final EnvCertProber certProber;

    public TlsAwareHttpSupport(EnvCertProber certProber) {
        this.certProber = certProber;
    }

    
    public TlsHttpResult get(String url, TlsPolicy policy, int timeoutMs) throws Exception {
        return exchange("GET", url, null, null, true, policy, timeoutMs);
    }

    
    public TlsHttpResult postJson(String url, Map<String, Object> body, TlsPolicy policy, int timeoutMs)
            throws Exception {
        return exchange("POST", url, body, null, true, policy, timeoutMs);
    }

    /**
     * Raw GET with request headers and redirects NOT followed: the caller
     * judges the bare status code. Following a redirect would land on the
     * login page and report a false-alive 200 (design doc M1 form two).
     */
    public TlsHttpResult getRaw(String url, Map<String, String> headers, TlsPolicy policy, int timeoutMs)
            throws Exception {
        return exchange("GET", url, null, headers, false, policy, timeoutMs);
    }

    private TlsHttpResult exchange(String method, String url, Map<String, Object> jsonBody,
                                   Map<String, String> headers, boolean followRedirects,
                                   TlsPolicy policy, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            applyTls(conn, policy);
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(followRedirects);
            if (headers != null) {
                headers.forEach(conn::setRequestProperty);
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] payload = toJson(jsonBody).getBytes(StandardCharsets.UTF_8);
                conn.getOutputStream().write(payload);
                conn.getOutputStream().flush();
            }
            int status = conn.getResponseCode();
            byte[] raw = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new TlsHttpResult(status, raw == null ? "" : new String(raw, StandardCharsets.UTF_8));
        } finally {
            conn.disconnect();
        }
    }

    
    private void applyTls(HttpURLConnection conn, TlsPolicy policy) throws Exception {
        if (!(conn instanceof HttpsURLConnection https) || policy == null
                || policy.mode() == TlsMode.STRICT) {
            return;
        }
        SSLContext ctx = certProber.clientSslContext(policy);
        if (ctx == null) {
            return;
        }
        https.setSSLSocketFactory(ctx.getSocketFactory());
        if (policy.mode() == TlsMode.INSECURE) {
            https.setHostnameVerifier((h, s) -> true);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return null;
        }
        try (in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            in.transferTo(bos);
            return bos.toByteArray();
        }
    }

    
    private static String toJson(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        body.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(escape(k)).append("\":\"").append(escape(String.valueOf(v))).append('"');
        });
        return sb.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

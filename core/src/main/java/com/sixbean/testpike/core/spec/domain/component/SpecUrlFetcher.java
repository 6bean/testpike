package com.sixbean.testpike.core.spec.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;

/**
 * <ul>
 * </ul>
 */
@Component
public class SpecUrlFetcher {

    
    public static final String KEY_ALLOW_HOSTS = "exec.http.allow-hosts";
    
    public static final String KEY_TIMEOUT_MS = "spec.url-fetch.timeout-ms";
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    private final SystemConfigReader configReader;

    public SpecUrlFetcher(SystemConfigReader configReader) {
        this.configReader = configReader;
    }

    
    public byte[] fetch(String url, int maxBytes) {
        URI uri = parseHttpUri(url);
        enforceAllowList(uri.getHost());
        return exchange(uri, maxBytes);
    }

    
    private URI parseHttpUri(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("订阅地址格式不正确，仅支持 http/https 链接",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ServiceException("订阅地址仅支持 http/https 链接，当前="
                    + (scheme == null ? "（缺协议）" : scheme), BizErrorCode.VALIDATION_ERROR);
        }
        return uri;
    }

    
    private void enforceAllowList(String host) {
        String allowHosts = configReader.getString(KEY_ALLOW_HOSTS, "");
        if (allowHosts.isBlank()) {
            return;
        }
        boolean allowed = Arrays.stream(allowHosts.split(","))
                .filter(p -> !p.isBlank())
                .anyMatch(pattern -> ProdBlacklistGuard.hostMatches(host, pattern));
        if (!allowed) {
            throw new ServiceException("地址主机 " + host + " 不在拉取白名单内"
                    + "（系统配置 exec.http.allow-hosts），请联系管理员加入后重试",
                    BizErrorCode.FORBIDDEN);
        }
    }

    private byte[] exchange(URI uri, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs());
            conn.setReadTimeout(timeoutMs());
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json, application/yaml, text/plain");
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                throw new ServiceException("订阅地址发生跳转（HTTP " + status
                        + "），出于安全不再跟随，请直接填写最终地址", BizErrorCode.VALIDATION_ERROR);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new ServiceException("拉取失败：上游返回 HTTP " + status,
                        BizErrorCode.VALIDATION_ERROR);
            }
            int declared = conn.getContentLength();
            if (declared > maxBytes) {
                throw new ServiceException("订阅内容超过大小上限 " + (maxBytes / 1024 / 1024)
                        + "MB（实际约 " + declared / 1024 + "KB）", BizErrorCode.VALIDATION_ERROR);
            }
            return readCapped(conn.getInputStream(), maxBytes);
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new ServiceException("订阅地址连接失败：" + e.getMessage()
                    + "，请检查 URL 可访问性与网络", BizErrorCode.VALIDATION_ERROR);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    
    private byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new ServiceException("订阅内容超过大小上限 " + (maxBytes / 1024 / 1024)
                        + "MB", BizErrorCode.VALIDATION_ERROR);
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private int timeoutMs() {
        return configReader.getInt(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    }
}

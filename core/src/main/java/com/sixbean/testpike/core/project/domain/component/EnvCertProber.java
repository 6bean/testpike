package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.common.util.TlsMode;
import com.sixbean.testpike.common.util.TlsPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * <ul>
 * </ul>
 */
@Component
public class EnvCertProber {

    private static final Logger log = LoggerFactory.getLogger(EnvCertProber.class);

    
    static final int TIMEOUT_MS = 5000;

    
    public CertProbeOutcome probe(String baseUrl, TlsPolicy policy) {
        if (baseUrl == null || !baseUrl.trim().toLowerCase().startsWith("https")) {
            return CertProbeOutcome.absent();
        }
        String url = baseUrl.trim();
        SSLContext collectCtx;
        try {
            collectCtx = trustAllContext();
        } catch (Exception e) {
            log.warn("证书采集上下文初始化失败（跳过观测）: {}", e.getMessage());
            return CertProbeOutcome.absent();
        }
        CertHandshakeResult collected = handshake(url, collectCtx);
        if (collected == null) {
            return CertProbeOutcome.absent();
        }
        TlsPolicy verifyPolicy = policy.mode() == TlsMode.INSECURE
                ? new TlsPolicy(TlsMode.STRICT, policy.caPem(), null, null)
                : policy;
        SSLContext verifyCtx = clientSslContext(verifyPolicy);
        if (verifyCtx == null) {
            try {
                verifyCtx = defaultContext();
            } catch (Exception e) {
                return new CertProbeOutcome(true, collected.tlsVersion(), collected.issuer(),
                        collected.notAfter(), false);
            }
        }
        CertHandshakeResult verified = handshake(url, verifyCtx);
        return new CertProbeOutcome(true, collected.tlsVersion(), collected.issuer(),
                collected.notAfter(), verified != null);
    }

    
    public SSLContext clientSslContext(TlsPolicy policy) {
        if (policy == null || policy.mode() == TlsMode.STRICT) {
            return null;
        }
        try {
            if (policy.mode() == TlsMode.INSECURE) {
                return trustAllContext();
            }
            return caTrustContext(policy.caPem());
        } catch (Exception e) {
            log.warn("TLS 信任上下文构建失败（失败安全=按 strict 校验处理）: {}", e.getMessage());
            return null;
        }
    }

    
    public HostnameVerifier hostnameVerifier(TlsPolicy policy) {
        if (policy != null && policy.mode() == TlsMode.INSECURE) {
            return (hostname, session) -> true;
        }
        return null;
    }


    
    private CertHandshakeResult handshake(String url, SSLContext ctx) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            if (conn instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(ctx.getSocketFactory());
                https.setHostnameVerifier((h, s) -> true);
            }
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.connect();
            if (!(conn instanceof HttpsURLConnection https)) {
                return null;
            }
            SSLSession session = https.getSSLSession().orElse(null);
            if (session == null) {
                return null;
            }
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                return null;
            }
            return new CertHandshakeResult(session.getProtocol(),
                    leaf.getIssuerX500Principal().getName(),
                    LocalDateTime.ofInstant(leaf.getNotAfter().toInstant(), ZoneId.systemDefault()));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        } };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx;
    }

    private static SSLContext defaultContext() throws Exception {
        return SSLContext.getDefault();
    }

    
    private static SSLContext caTrustContext(String caPem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        java.security.KeyStore ks = java.security.KeyStore.getInstance(
                java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        int idx = 0;
        for (Certificate cert : cf.generateCertificates(
                new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8)))) {
            ks.setCertificateEntry("ca-" + idx++, cert);
        }
        if (idx == 0) {
            throw new IllegalStateException("CA bundle 未解析出任何证书");
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());
        return ctx;
    }
}

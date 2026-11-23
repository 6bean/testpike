package com.sixbean.testpike.core.cluster.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.core.cluster.api.dto.ArtifactPrecheckItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pre-dispatch artifact reachability prober (upgrade wizard step-2 gate).
 * <p>For every declared download artifact: whitelist decides FIRST (a host outside
 * {@link ArtifactHostPolicy} gets a blocked row and zero outbound traffic — SSRF /
 * supply-chain red line, payload URLs are never followed), then a HEAD request with
 * {@code Range: bytes=0-0} asks the origin "are you there and how big". The whole
 * package is never downloaded (a wizard pre-check must stay inside a 10s budget even
 * for multi-hundred-MB jars).
 * <p>Verdicts (single spectrum, consumed by the impact view; no second copy):
 * reachable / unreachable (HTTP error or connection refused — "fix the mirror before
 * rolling") / size_mismatch (declared size vs origin-reported total length disagree =
 * silent truncation or replacement) / host_blocked. An artifact that declares no size
 * is judged only on reachability (honest: nothing to compare against).
 */
@Component
public class ReleaseArtifactProber {

    private static final Logger log = LoggerFactory.getLogger(ReleaseArtifactProber.class);

    /** Probe timeout K8 key (hot-updatable; default 10s keeps one wizard click ≈ worst case 30s). */
    public static final String PROBE_TIMEOUT_KEY = "testpike.cluster.artifact-probe-timeout-seconds";
    static final int DEFAULT_PROBE_TIMEOUT_SECONDS = 10;

    private final ArtifactHostPolicy hostPolicy;
    private final com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    public ReleaseArtifactProber(ArtifactHostPolicy hostPolicy,
            com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder) {
        this.hostPolicy = hostPolicy;
        this.configCacheHolder = configCacheHolder;
    }

    /**
     * Probe every item of an artifact array against the current whitelist.
     * Returns one view row per item, in declaration order; empty input = empty list
     * (never a fabricated red).
     */
    public List<ArtifactPrecheckItemView> precheck(List<JsonNode> artifacts) {
        List<ArtifactPrecheckItemView> rows = new ArrayList<>();
        if (artifacts == null || artifacts.isEmpty()) {
            return rows;
        }
        Set<String> allowed = hostPolicy.allowedHosts();
        Duration timeout = Duration.ofSeconds(probeTimeoutSeconds());
        for (JsonNode item : artifacts) {
            rows.add(probeOne(item, allowed, timeout));
        }
        return rows;
    }

    private ArtifactPrecheckItemView probeOne(JsonNode item, Set<String> allowed, Duration timeout) {
        String family = item.path("component_family").asText("");
        String url = item.path("url").asText("");
        long declared = item.path("size").asLong(0);

        String host = ReleaseArtifactRules.hostOf(url);
        if (host == null || !ReleaseArtifactRules.hostAllowed(item, allowed)) {
            return new ArtifactPrecheckItemView(family, url, declared, -1,
                    ArtifactPrecheckItemView.VERDICT_HOST_BLOCKED,
                    "工件 " + family + " 的下载域不在白名单内（键 "
                            + ArtifactHostPolicy.ALLOWED_HOSTS_KEY
                            + "，镜像源域自动并入）——白名单外 URL 禁止发起任何探测请求，防 SSRF/供应链替换：先修正登记域或更新白名单再预检");
        }

        ProbeOutcome outcome = headProbe(url, timeout);
        if (!outcome.reached()) {
            return new ArtifactPrecheckItemView(family, url, declared, -1,
                    ArtifactPrecheckItemView.VERDICT_UNREACHABLE,
                    "工件 " + family + " 下载件不可达（" + outcome.detail()
                            + "）——先修复镜像源/网络后重新预检；带坏包发起滚动升级会把节点升进坑里");
        }
        if (declared > 0 && outcome.totalLength() > 0 && declared != outcome.totalLength()) {
            return new ArtifactPrecheckItemView(family, url, declared, outcome.totalLength(),
                    ArtifactPrecheckItemView.VERDICT_SIZE_MISMATCH,
                    "工件 " + family + " 声明大小 " + declared + " 字节与源站实际 "
                            + outcome.totalLength() + " 字节不符——文件可能被截断或被替换，禁发起升级；重新构建并核对 sha256 后重登");
        }
        return new ArtifactPrecheckItemView(family, url, declared, outcome.totalLength(),
                ArtifactPrecheckItemView.VERDICT_REACHABLE,
                "工件 " + family + " 可达（HEAD " + outcome.detail() + "）");
    }

    /**
     * One HEAD probe. Range bytes=0-0 makes compliant origins answer 206 with
     * content-range (still "there"); any 2xx/3xx counts as reachable. 4xx/5xx or
     * connect/timeout failure = unreachable with the concrete cause in the sentence.
     */
    private ProbeOutcome headProbe(String url, Duration timeout) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER) // whitelist host only; no redirect chasing
                    .connectTimeout(timeout)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("Range", "bytes=0-0")
                    .timeout(timeout)
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            boolean ok = status >= 200 && status < 400;
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (status == 206) {
                total = rangeTotal(response.headers().firstValue("Content-Range").orElse(""));
            }
            if (!ok) {
                return new ProbeOutcome(false, "HTTP " + status, -1);
            }
            return new ProbeOutcome(true, "HTTP " + status, total);
        } catch (java.net.ConnectException e) {
            // getMessage() is null on refused-connection (JDK behaviour) — name the
            // concrete operator-facing cause instead of leaking a bare "null"
            return new ProbeOutcome(false, "连接被拒绝（目标端口无监听：镜像源未启动或 URL 主机端口写错）", -1);
        } catch (java.net.http.HttpTimeoutException e) {
            return new ProbeOutcome(false, "探测超时（" + timeout.toSeconds() + " 秒）", -1);
        } catch (Exception e) {
            log.warn("artifact probe failed for {}: {}", url, e.toString());
            return new ProbeOutcome(false, e.getClass().getSimpleName() + ": " + e.getMessage(), -1);
        }
    }

    /** 206 Content-Range "bytes 0-0/12345" → total 12345; unparseable = -1 (no fake size). */
    private static long rangeTotal(String contentRange) {
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return -1;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** K8 integer key read-now (invalid/missing = conservative default; same posture as sibling services). */
    private int probeTimeoutSeconds() {
        String raw = configCacheHolder.get(PROBE_TIMEOUT_KEY);
        if (raw != null) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value >= 1 && value <= 120) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default (key-driven fool-proofing)
            }
        }
        return DEFAULT_PROBE_TIMEOUT_SECONDS;
    }

    /** Probe result: reached origin + human detail + origin-reported total size (-1 = unknown). */
    private record ProbeOutcome(boolean reached, String detail, long totalLength) {
    }
}

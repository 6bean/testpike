package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.api.dto.ClusterUpstreamProbeResult;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateDetectedInput;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateInfo;
import com.sixbean.testpike.core.cluster.domain.component.ComponentSupportRules;
import com.sixbean.testpike.core.cluster.domain.component.ReleaseCandidateAlerter;
import com.sixbean.testpike.core.cluster.domain.component.UpstreamFeedKind;
import com.sixbean.testpike.core.cluster.domain.component.UpstreamFeedSource;
import com.sixbean.testpike.core.cluster.domain.component.UpstreamProbeOutcome;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.cluster.domain.service.ClusterUpstreamProbeService;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseCandidateService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implements upstream version probe writeback for the frozen scan-loop section named upstream version probe.
 * The chain deduplicates existing engine-runtime families in the ledger, performs one upstream probe per family, and shares one result frame across multiple node rows in the same family.
 * Prohibits duplicate external calls per node and routes the target through the existing upsert channel of {@code ClusterComponentService}.
 * COALESCE semantics refresh when target provides a value, while the current value is returned unchanged from the ledger row so the probe side must not lose the heartbeat current value under the same protection.
 * The decision matrix automatically re-evaluates without new decision code.
 * The honest boundary in the frozen document red line prohibits using an old value as the latest value.
 * When a probe fails, returns non-200, or cannot be parsed, the family target is not written, the original baseline remains sticky, and an unreachable external probe trace is recorded.
 * For families without a registered upstream source, such as wx_devtools or hdc with no public version source, a not-probed trace is recorded without crashing or faking a result.
 * ③ platform-side families (core/admin/api/ai-service/frontend/mock-server) = the
 * release-feed rerouted set :
 * A ledger hit merges into one aggregate probe, prohibits per-family external calls, and writes the result back to the tp_release_candidate table.
 * On the perception side, writing the authoritative tp_release_manifest table and the ledger target is prohibited because release authority is manual.
 * Target promotion is a post-confirmation action, and the infra families are executor-agent, browser-kernel, and db-schema.
 * They are still designed to be silently skipped with zero external calls.
 * Mirror rerouting reads installkit.mirror-base-url directly under the same standard, prohibits secondary caching, and takes effect immediately on hot update.
 * npm maps to {mirror}/npm/{pkg}, pypi maps to {mirror}/pypi/{pkg}/json, and the GitHub releases mirror side is referenced.
 * The GitHub releases mirror side has no corresponding rewrite semantics because intranet traffic generally uses a GitHub proxy rather than an npm mirror, so the configured source is kept as an honest boundary.
 * Do not fabricate mirror paths.
 * The source address is a Spring configuration item, including testpike.cluster.probe.*-base and the migrated flaky-sentinel.cron.
 * It uses the same standard as a non-tp_sys_config key, defaults to official sources, and enforces a hard 10-second connection or request timeout gate.
 */
@Service
public class ClusterUpstreamProbeServiceImpl implements ClusterUpstreamProbeService {

    private static final Logger log = LoggerFactory.getLogger(ClusterUpstreamProbeServiceImpl.class);

 /** The component-family upstream source registry lists engine official sources and uses family names from the decision-matrix registered family standard. */
    private static final Map<String, UpstreamFeedSource> FEED_REGISTRY = Map.of(
            "engine-runtime.node",
            new UpstreamFeedSource(UpstreamFeedKind.GITHUB_RELEASES, "repos/nodejs/node"),
            "engine-runtime.maestro",
            new UpstreamFeedSource(UpstreamFeedKind.GITHUB_RELEASES, "repos/mobile-dev-inc/maestro"),
            "engine-runtime.appium",
            new UpstreamFeedSource(UpstreamFeedKind.NPM, "appium"),
            "engine-runtime.minium",
            new UpstreamFeedSource(UpstreamFeedKind.PYPI, "minium"),
            "engine-runtime.mobilecli",
            new UpstreamFeedSource(UpstreamFeedKind.GITHUB_RELEASES, "repos/mobile-next/mobilecli"));

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
 * Release-feed rerouted families = the six platform pieces (mock-server
 * joined at ; the probe-surface family caliber).
 * Single source = ComponentSupportRules.PLATFORM_RELEASE_FAMILIES (the
 * platform release set; the three infra families keep no platform release
 * feed semantics and stay silently skipped there).
 */
    private static final Set<String> RELEASE_TRACKED =
            ComponentSupportRules.PLATFORM_RELEASE_FAMILIES;

    private final ClusterComponentService componentService;
    private final ConfigCacheHolder configCacheHolder;
    private final ReleaseCandidateService releaseCandidateService;
    private final ReleaseCandidateAlerter releaseCandidateAlerter;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${testpike.cluster.probe.npm-base:https://registry.npmjs.org}")
    private String npmBase;

    @Value("${testpike.cluster.probe.pypi-base:https://pypi.org}")
    private String pypiBase;

    @Value("${testpike.cluster.probe.github-base:https://api.github.com}")
    private String githubBase;

    /** The release feed repository is specified as owner/repo, defaults to the official repository for zero-config use, and a blank value explicitly disables it. */
    @Value("${testpike.cluster.release-feed.repo:6bean/testpike}")
    private String releaseFeedRepo;

    /** The release feed access token is injected only through .env or environment variables, an empty value sends no Authorization header, and it must not be persisted to the database or logged. */
    @Value("${testpike.cluster.release-feed.token:}")
    private String releaseFeedToken;

    public ClusterUpstreamProbeServiceImpl(ClusterComponentService componentService,
                                           ConfigCacheHolder configCacheHolder,
                                           ReleaseCandidateService releaseCandidateService,
                                           ReleaseCandidateAlerter releaseCandidateAlerter) {
        this.componentService = componentService;
        this.configCacheHolder = configCacheHolder;
        this.releaseCandidateService = releaseCandidateService;
        this.releaseCandidateAlerter = releaseCandidateAlerter;
        this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    @Override
    public ClusterUpstreamProbeResult probeTargets() {
        List<ClusterComponentInfo> ledger = componentService.listAll();
        // A family maps to its ledger rows, shares one upstream probe result frame across the family, and writes back results at the row level for each node.
        Map<String, List<ClusterComponentInfo>> rowsByFamily = new LinkedHashMap<>();
        boolean releaseTracked = false;
        for (ClusterComponentInfo row : ledger) {
            String family = row.componentFamily();
            if (RELEASE_TRACKED.contains(family)) {
                // Platform families use the release-feed rerouting side, record a ledger hit, and aggregate one external call.
                releaseTracked = true;
                continue;
            }
            if (!family.startsWith(ComponentSupportRules.ENGINE_PREFIX)) {
                continue; // Infra families such as executor-agent, browser-kernel, and db-schema are silently skipped by design with zero noise.
            }
            rowsByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(row);
        }

        String mirror = mirrorBaseUrl();
        List<String> notes = new ArrayList<>();
        int probed = 0;
        int written = 0;
        for (Map.Entry<String, List<ClusterComponentInfo>> entry : rowsByFamily.entrySet()) {
            String family = entry.getKey();
            UpstreamFeedSource source = FEED_REGISTRY.get(family);
            if (source == null) {
                notes.add(family + ": 未探测（上游无登记公开版本源，禁硬造探测路径冒充纳管）");
                continue;
            }
            UpstreamProbeOutcome outcome = fetchLatest(source, mirror);
            if (outcome.version() == null) {
                notes.add(family + ": 外网探测不可达（" + outcome.reason()
                        + "）——target 保持原基准粘滞，禁旧值冒充");
                continue;
            }
            probed++;
            written += writeTarget(family, outcome.version(), entry.getValue());
        }
        int candidatesRecorded = 0;
        if (releaseTracked) {
            // Per-family external calls are prohibited because five platform families share one aggregated release-feed probe and merge the result into the trace.
            ClusterUpstreamProbeResult feed = probeReleaseFeed();
            candidatesRecorded = feed.candidatesRecorded();
            notes.addAll(feed.unresolvedNotes());
        }
        if (!notes.isEmpty()) {
            log.info("上游版本探测留痕 {} 条：{}", notes.size(), notes);
        }
        return new ClusterUpstreamProbeResult(probed, written, notes, candidatesRecorded);
    }

    @Override
    public ClusterUpstreamProbeResult probeReleaseFeed() {
        String repo = releaseFeedRepo == null ? "" : releaseFeedRepo.trim();
        if (repo.isBlank()) {
            // Type 3 treats a blank value as explicit disable, records the user's active choice, and neither probes nor fakes a result.
            return new ClusterUpstreamProbeResult(0, 0,
                    List.of("release-feed: 显式关闭不探测（testpike.cluster.release-feed.repo 置空白）"), 0);
        }
        String url = githubBase + "/repos/" + repo + "/releases/latest";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json");
            String token = releaseFeedToken == null ? "" : releaseFeedToken.trim();
            if (!token.isBlank()) {
                // The token is injected only through .env or environment variables, must not be persisted to the database or logged, and an empty value means anonymous probing.
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = httpClient.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new ClusterUpstreamProbeResult(0, 0, List.of(
                        "release-feed: 外网探测不可达（" + url + " 返回 " + response.statusCode()
                                + "）——候选保持粘滞，禁旧值冒充"), 0);
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode tag = root.path("tag_name");
            if (tag.isMissingNode() || tag.asText().isBlank()) {
                // Type 2 does not guess on structural drift and prohibits using other fields to fabricate a version number.
                return new ClusterUpstreamProbeResult(0, 0, List.of(
                        "release-feed: feed 结构漂移（响应缺 tag_name，" + url + "）——禁猜版本零写候选"), 0);
            }
            String raw = tag.asText().trim();
            String version = raw.startsWith("v") ? raw.substring(1) : raw;
            String payload = buildCandidatePayload(root, repo);
            String channel = root.path("prerelease").asBoolean(false) ? "prerelease" : "released";
            ReleaseCandidateInfo before = releaseCandidateService.findByVersion(version);
            ReleaseCandidateInfo saved = releaseCandidateService.recordDetected(
                    new ReleaseCandidateDetectedInput(version, channel, "poll", payload));
            // Notifications are sent only on the first detected state, and re-probes that are already detected or handled are not repeated to avoid spamming.
            boolean freshDetected = (before == null || before.status().equals("detected"))
                    && "detected".equals(saved.status());
            if (freshDetected) {
                releaseCandidateAlerter.alert(version,
                        "探测到 testPike 新版本 " + version + "（通道：" + channel
                                + "，来源：官方 release feed " + repo + "）。"
                                + "进入「升级中心 → 版本候选」确认后将转正式发布清单。");
            }
            return new ClusterUpstreamProbeResult(0, 0, List.of(),
                    freshDetected ? 1 : 0);
        } catch (Exception e) {
            return new ClusterUpstreamProbeResult(0, 0, List.of(
                    "release-feed: 外网探测不可达（" + url + " 探测异常 "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "）——候选保持粘滞，禁旧值冒充"), 0);
        }
    }

    /** The candidate frame retains key fields from the raw feed as structured data for changelog drafts and confirmation-drawer prefill consumption, without guessing or creating missing fields. */
    private String buildCandidatePayload(JsonNode root, String repo) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("release_name", root.path("name").asText(""));
        payload.put("release_notes_md", root.path("body").asText(""));
        payload.put("published_at", root.path("published_at").asText(""));
        var assets = payload.putArray("assets");
        for (JsonNode asset : root.path("assets")) {
            assets.add(asset.path("name").asText(""));
        }
        payload.put("detected_repo", repo);
        return payload.toString();
    }

    private int writeTarget(String family, String target, List<ClusterComponentInfo> rows) {
        int landed = 0;
        for (ClusterComponentInfo row : rows) {
            try {
 // Returning the current value unchanged prevents the probe side from losing heartbeat current values under upsert overwrite semantics, with the same protection.
                componentService.upsert(row.nodeId(), family, row.currentVersion(),
                        "scan", null, target);
                landed++;
            } catch (RuntimeException e) {
                log.warn("上游金基准回写单行失败（诚实跳过该行）: node={} family={} cause={}",
                        row.nodeId(), family, e.getMessage());
            }
        }
        return landed;
    }

    private UpstreamProbeOutcome fetchLatest(UpstreamFeedSource source, String mirror) {
        String url = feedUrl(source, mirror);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return UpstreamProbeOutcome.failed(url + " 返回 " + response.statusCode());
            }
            String version = parseVersion(source.kind(), response.body());
            return version == null
                    ? UpstreamProbeOutcome.failed(url + " 响应缺少版本字段（feed 结构漂移）")
                    : UpstreamProbeOutcome.ok(version);
        } catch (Exception e) {
            return UpstreamProbeOutcome.failed(url + " 探测异常 "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Probe URL assembly reroutes npm and pypi when the mirror key is present, while GitHub always uses the configured source because it has no npm mirror rewrite semantics. */
    private String feedUrl(UpstreamFeedSource source, String mirror) {
        return switch (source.kind()) {
            case NPM -> (mirror.isBlank() ? npmBase : mirror + "/npm") + "/" + source.ref();
            case PYPI -> (mirror.isBlank() ? pypiBase : mirror) + "/pypi/" + source.ref() + "/json";
            case GITHUB_RELEASES -> githubBase + "/" + source.ref() + "/releases/latest";
        };
    }

    /** JSON parsing for each source returns null on structural drift and records an unreachable trace without guessing. */
    private String parseVersion(UpstreamFeedKind kind, String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode node = switch (kind) {
            case NPM -> root.path("dist-tags").path("latest");
            case PYPI -> root.path("info").path("version");
            case GITHUB_RELEASES -> root.path("tag_name");
        };
        if (node.isMissingNode() || node.asText().isBlank()) {
            return null;
        }
        String raw = node.asText().trim();
        // Common v prefixes in GitHub tags are stripped before persisting to the database, keeping the decision-side version standard single-sourced and giving VersionCompare the same tolerance.
        return kind == UpstreamFeedKind.GITHUB_RELEASES && raw.startsWith("v")
                ? raw.substring(1) : raw;
    }

    /** The mirror source is read directly, missing or blank values become an empty string representing the official source, secondary caching is prohibited, hot updates take effect immediately, and installkit uses the same base path. */
    private String mirrorBaseUrl() {
        String raw = configCacheHolder.get("installkit.mirror-base-url");
        return raw == null ? "" : raw.trim();
    }
}

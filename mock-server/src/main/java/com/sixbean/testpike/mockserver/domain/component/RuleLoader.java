package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Pull-based rule loader: polls the core version-pinned snapshot and holds the
 * last good content version. Red window: polling logic not wired yet — the
 * pin stays empty until GREEN implements the fetch.
 */
@Component
public class RuleLoader {

    /** Tolerant reader for the snapshot envelope (unknown future fields ignored). */
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final RestClient coreClient;
    private final String spaceKey;
    private final long stalenessWarnSeconds;
    private final MockRuleIndex ruleIndex;
    /** Machine parser rides the shared declaration kernel (constructed like the index). */
    private final MockScenarioMachines scenarioMachines =
            new MockScenarioMachines(new com.sixbean.testpike.common.mock.MockScenarioRules());

    private volatile String pinnedVersion;
    private volatile String pinnedSpaceKey;
    /**
 * Space row id and owning project of the pinned snapshot :
 * the A-3 answer door is space-scoped and the generation leg needs the
 * project as its LLM routing anchor. Tolerant read — an older core
 * snapshot without these fields decodes to null, and a null identity
 * simply keeps the AI rung switched off for that pin (no guess).
 */
    private volatile Long pinnedSpaceId;
    private volatile Long pinnedProjectId;
    /**
 * Open cassette box of the pinned snapshot : non-null means
 * the proxy forwarder may forward and record misses; null (field absent or
 * JSON null) is the honest open state — no box, no forwarding. Re-pinned on
 * every content drift, never carried over stale from an older pin.
 */
    private volatile Long activeRecordingId;
    /**
 * Gated proxy target of the pinned space row : the core
 * allowlist gate wrote it onto the space row before the box opened, so the
 * forwarder consumes this pinned value and never re-judges the allowlist
 * (single-source rule; V232 note). Null = the space was never bound.
 */
    private volatile String proxyTargetUrl;
    /**
 * Chaos dial of the pinned snapshot : percent of served
 * requests the fault kernel may answer with a plain 503. Tolerant read —
 * a missing or null field (older core envelope) decodes to 0 = no
 * injection, never a guess. Rides the same pin swap as the proxy target:
 * the kernel only ever sees a dial from the generation the rules were
 * served under.
 */
    private volatile int chaosPercent;
    /**
 * Miss ladder choice of the pinned space row : passthrough
 * forwards unmatched traffic to the bound target whenever one is set,
 * reject answers the plain 404+trace, llm-fallback (the default) runs the
 * derived pre-policy ladder. Tolerant read — an absent or unknown value
 * (older core generation) decodes to llm-fallback, never a guess that
 * silently turns forwarding or the AI rung off.
 */
    private volatile String missPolicy =
            com.sixbean.testpike.common.mock.MockMissPolicies.DEFAULT_POLICY;
    private volatile LocalDateTime lastSuccessAt;
    private volatile int consecutiveFailures;
    private volatile String lastError;

    public RuleLoader(RestClient coreClient,
                      @Value("${testpike.mock.load.space-key:}") String spaceKey,
                      @Value("${testpike.mock.load.staleness-warn-seconds:30}") long stalenessWarnSeconds) {
        this.coreClient = coreClient;
        this.spaceKey = spaceKey;
        this.stalenessWarnSeconds = stalenessWarnSeconds;
        this.ruleIndex = new MockRuleIndex();
    }

    /** Background poll tick; cadence comes from configuration, never hardcoded. */
    @Scheduled(fixedDelayString = "${testpike.mock.load.interval-milliseconds:3000}")
    public void poll() {
        refresh();
    }

    /** Fetch one snapshot and re-pin on content drift. */
    public void refresh() {
        if (spaceKey == null || spaceKey.isBlank()) {
            // no routing handle configured: stay idle; fail-closed comes from having no pin
            return;
        }
        try {
            String json = coreClient.get()
                    .uri(uri -> uri.path("/internal/mock/snapshot")
                            .queryParam("spaceKey", spaceKey).build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = SNAPSHOT_MAPPER.readTree(json == null ? "{}" : json);
            if (!"0".equals(root.path("code").asText(""))) {
                recordFailure("core rejected the snapshot fetch (code="
                        + root.path("code").asText("?") + ")");
                return;
            }
            String version = root.path("data").path("version").asText(null);
            if (version == null || version.isBlank()) {
                recordFailure("snapshot arrived without a content version");
                return;
            }
            if (version.equals(pinnedVersion)) {
                // no content drift: the pinned index is already the served truth
                this.lastSuccessAt = LocalDateTime.now();
                this.consecutiveFailures = 0;
                this.lastError = null;
                return;
            }
            // Parse before publishing: an unparsable row must not tear down the
            // index that live traffic is currently served from. Machines and
            // stubs swap as one unit — a rule set stepped against the previous
            // machine map would advance lanes onto undeclared states.
            List<MockLoadedStub> loaded = MockRuleIndex.parseStubs(root.path("data").path("stubs"));
            Map<String, MockScenarioMachine> machines =
                    scenarioMachines.parse(root.path("data").path("scenarios"));
            // Tolerant read of the recording slot: absent field or JSON null
            // both decode to null (open state). The box id rides the same pin
            // swap as the rule set — the forwarder never sees an id from a
            // snapshot generation the rules were not served under.
            JsonNode recordingNode = root.path("data").path("activeRecordingId");
            Long nextRecordingId =
                    (recordingNode.isMissingNode() || recordingNode.isNull()) ? null
                            : recordingNode.asLong();
            // The gated proxy target rides the same pin swap: the forwarder
            // only ever sees a value from the snapshot generation the rules
            // were served under (never a stale box aimed at a new target).
            JsonNode targetNode = root.path("data").path("space").path("proxyTargetUrl");
            String nextProxyTarget =
                    (targetNode.isMissingNode() || targetNode.isNull()) ? null
                            : targetNode.asText();
            // The chaos dial rides the same pin swap: tolerant read, an absent
            // or null field (older core generation) decodes to 0 = no injection.
            JsonNode chaosNode = root.path("data").path("space").path("chaosPercent");
            int nextChaos =
                    (chaosNode.isMissingNode() || chaosNode.isNull()) ? 0
                            : Math.max(0, Math.min(100, chaosNode.asInt(0)));
            // The miss policy rides the same pin swap: tolerant read — an
            // absent, null or unknown value (older core generation) decodes to
            // the derived ladder, never a silent rung switch-off.
            JsonNode policyNode = root.path("data").path("space").path("missPolicy");
            String nextPolicy =
                    (policyNode.isMissingNode() || policyNode.isNull())
                            ? com.sixbean.testpike.common.mock.MockMissPolicies.DEFAULT_POLICY
                            : (com.sixbean.testpike.common.mock.MockMissPolicies
                                    .isKnown(policyNode.asText())
                                    ? policyNode.asText()
                                    : com.sixbean.testpike.common.mock.MockMissPolicies.DEFAULT_POLICY);
            // Space identity rides the same pin swap : tolerant read,
            // absent fields decode to null = the AI rung stays off for this pin.
            JsonNode spaceNode = root.path("data").path("space");
            JsonNode spaceIdNode = spaceNode.path("id");
            Long nextSpaceId = (spaceIdNode.isMissingNode() || spaceIdNode.isNull())
                    ? null : spaceIdNode.asLong();
            JsonNode projectIdNode = spaceNode.path("projectId");
            Long nextProjectId = (projectIdNode.isMissingNode() || projectIdNode.isNull())
                    ? null : projectIdNode.asLong();
            ruleIndex.replaceAll(loaded, machines);
            this.pinnedVersion = version;
            this.pinnedSpaceKey = spaceKey;
            this.activeRecordingId = nextRecordingId;
            this.proxyTargetUrl = nextProxyTarget;
            this.chaosPercent = nextChaos;
            this.missPolicy = nextPolicy;
            this.pinnedSpaceId = nextSpaceId;
            this.pinnedProjectId = nextProjectId;
            this.lastSuccessAt = LocalDateTime.now();
            this.consecutiveFailures = 0;
            this.lastError = null;
        } catch (Exception ex) {
            recordFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /** The rule index fed by this loader — the traffic plane reads it per request. */
    public MockRuleIndex ruleIndex() {
        return ruleIndex;
    }

    /** Remember an unsuccessful poll; the previous good pin is never dropped. */
    private void recordFailure(String reason) {
        this.consecutiveFailures++;
        this.lastError = reason;
    }

    public boolean everLoaded() {
        return pinnedVersion != null;
    }

    public String pinnedVersion() {
        return pinnedVersion;
    }

    public String pinnedSpaceKey() {
        return pinnedSpaceKey;
    }

    /** Space row id of the held pin (null on an older snapshot = AI rung off). */
    public Long pinnedSpaceId() {
        return pinnedSpaceId;
    }

    /** Owning project of the held pin (generation routing anchor; null = off). */
    public Long pinnedProjectId() {
        return pinnedProjectId;
    }

    /** Open cassette box id of the current pin (null = no recording runs). */
    public Long activeRecordingId() {
        return activeRecordingId;
    }

    /** Gated proxy target of the current pin (null = space never bound). */
    public String proxyTargetUrl() {
        return proxyTargetUrl;
    }

    /** Miss ladder of the current pin . */
    public String missPolicy() {
        return missPolicy;
    }

    /** Chaos dial of the current pin 0-100 (0 = no space-level injection). */
    public int chaosPercent() {
        return chaosPercent;
    }

    public LocalDateTime lastSuccessAt() {
        return lastSuccessAt;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public String lastError() {
        return lastError;
    }

    /** Stale = nothing pinned, or the pin could not be confirmed on recent polls. */
    public boolean isStale() {
        if (!everLoaded()) {
            return true;
        }
        if (consecutiveFailures > 0) {
            return true;
        }
        return lastSuccessAt == null
                || lastSuccessAt.plusSeconds(stalenessWarnSeconds).isBefore(LocalDateTime.now());
    }
}

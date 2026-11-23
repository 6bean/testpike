package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.webelement.infrastructure.remote.client.AiServiceElementNamingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relay-side naming enrichment for survey candidate pools (WE batch segment P3,
 * core face). Rewrites only the {@code name} field of candidate nodes keyed
 * by anchor identity, across the default tier AND viewport tiers sharing one
 * identity; ingestion semantics stay single-sourced in
 * {@link WebElementSurveyCollector} (nameOf keeps its placeholder role for
 * nodes nobody named).
 * <p>Honest-degrade contract: malformed pool, anchor-less pool, transport
 * failure or an empty name map all return the ORIGINAL pool string — same
 * reference, byte-identical — so the ledger path keeps its rule-collected
 * names and a model outage can never blank out a survey.
 */
@Component
public class WebElementSurveyNaming {

    private static final Logger log = LoggerFactory.getLogger(WebElementSurveyNaming.class);

    private final AiServiceElementNamingClient namingClient;
    private final ObjectMapper objectMapper;

    public WebElementSurveyNaming(AiServiceElementNamingClient namingClient,
                                  ObjectMapper objectMapper) {
        this.namingClient = namingClient;
        this.objectMapper = objectMapper;
    }

    /**
 * @param poolJson raw candidate pool from the explore step journal
 * @param sessionId explore session owning the survey process stream —
 * forwarded verbatim so the naming face can land one frame per
 * batch (R62 segment b2); null skips only the frame
 * @return pool with AI names applied when available; otherwise poolJson
 * itself (unchanged reference)
 */
    public String namePool(long projectId, String pageUrl, Long sessionId, String poolJson) {
        return namePoolOutcome(projectId, pageUrl, sessionId, poolJson).pool();
    }

    /**
     * Same enrichment door as {@link #namePool}, plus the honest naming-supply
     * reading the whole-site run ledger tallies degraded pages from: only a
     * pool that owed names and could not get them counts as an outage
     * (transport failure, empty name map, or a failed rewrite). Malformed or
     * anchor-less pools never were, so they report not-degraded.
     */
    public NamingOutcome namePoolOutcome(long projectId, String pageUrl, Long sessionId,
                                         String poolJson) {
        if (poolJson == null || poolJson.isBlank()) {
            return new NamingOutcome(poolJson, false);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(poolJson);
        } catch (Exception malformed) {
            return new NamingOutcome(poolJson, false);
        }
        LinkedHashMap<String, Map<String, String>> descriptors = new LinkedHashMap<>();
        collectTier(root.path("candidates"), descriptors);
        for (JsonNode tier : root.path("variants")) {
            collectTier(tier.path("candidates"), descriptors);
        }
        if (descriptors.isEmpty()) {
            return new NamingOutcome(poolJson, false);
        }
        Map<String, String> names;
        try {
            names = namingClient.nameCandidates(projectId, pageUrl, sessionId,
                    List.copyOf(descriptors.values()));
        } catch (Exception remoteFailure) {
            log.warn("测绘命名接力失败，候选保持规则采集名 projectId={}: {}",
                    projectId, remoteFailure.getMessage());
            return new NamingOutcome(poolJson, true);
        }
        if (names == null || names.isEmpty()) {
            return new NamingOutcome(poolJson, true);
        }
        try {
            rewriteTier(root.path("candidates"), names);
            for (JsonNode tier : root.path("variants")) {
                rewriteTier(tier.path("candidates"), names);
            }
            return new NamingOutcome(objectMapper.writeValueAsString(root), false);
        } catch (Exception rewriteFailure) {
            log.warn("测绘命名回写失败，候选保持原池 projectId={}: {}",
                    projectId, rewriteFailure.getMessage());
            return new NamingOutcome(poolJson, true);
        }
    }

    private void collectTier(JsonNode candidates,
                             LinkedHashMap<String, Map<String, String>> into) {
        if (!candidates.isArray()) {
            return;
        }
        for (JsonNode node : candidates) {
            LinkedHashMap<String, String> anchors = WebElementSurveyCollector.anchorsOf(node);
            if (anchors.isEmpty()) {
                continue;
            }
            String identity = WebElementSurveyCollector.identityOf(anchors);
            into.putIfAbsent(identity, descriptorOf(identity, node));
        }
    }

    private static Map<String, String> descriptorOf(String identity, JsonNode node) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("key", identity);
        row.put("text", node.path("text").asText("").trim());
        row.put("role", node.path("role").asText("").trim());
        row.put("nearby", node.path("nearby").asText("").trim());
        return row;
    }

    private void rewriteTier(JsonNode candidates, Map<String, String> names) {
        if (!candidates.isArray()) {
            return;
        }
        for (JsonNode node : candidates) {
            LinkedHashMap<String, String> anchors = WebElementSurveyCollector.anchorsOf(node);
            if (anchors.isEmpty() || !(node instanceof ObjectNode target)) {
                continue;
            }
            String aiName = names.get(WebElementSurveyCollector.identityOf(anchors));
            if (aiName != null && !aiName.isBlank()) {
                target.put("name", aiName.trim());
            } else if (target.path("name").asText("").isBlank()
                    && !target.path("text").asText("").isBlank()) {
                target.put("name", target.path("text").asText("").trim());
            }
        }
    }
}

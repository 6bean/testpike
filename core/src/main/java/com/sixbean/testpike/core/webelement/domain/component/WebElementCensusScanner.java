package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * WE-S6 census scanner : read-only survey of the
 * project's web case scripts — every inline locator is folded onto the
 * normalized page key and grouped by the ledger's anchor identity, so the
 * elements repeated across the most scripts surface first as migration
 * candidates. This piece deliberately produces no writes: batch ledger
 * entry and script reference replacement (draft-card human approval,
 * per-case rollback, diff trail) ride on top of this ranking in the next
 * slices.
 * <p>Extraction rides {@link WebElementInlineAnchorWalk} and the survey
 * collector's anchor functions — the same single source the save-door
 * registration uses (R40), so census and ledger can never disagree on what
 * "the same element" means.
 */
@Component
public class WebElementCensusScanner {

    private final ObjectMapper mapper;
    private final WebElementPageKeys pageKeys;

    public WebElementCensusScanner(ObjectMapper mapper, WebElementPageKeys pageKeys) {
        this.mapper = mapper;
        this.pageKeys = pageKeys;
    }

    /** Census one batch of scripts: scriptId to raw action document. */
    public WebElementCensusReport scan(Map<Long, String> actionJsonByScript) {
        LinkedHashMap<String, LinkedHashMap<String, String>> anchorsByGroup =
                new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<Long>> scriptsByGroup = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> stepsByGroup = new LinkedHashMap<>();
        int scanned = 0;
        int parseFailures = 0;
        int unattributed = 0;

        for (Map.Entry<Long, String> doc : actionJsonByScript.entrySet()) {
            scanned++;
            JsonNode root;
            try {
                root = mapper.readTree(doc.getValue());
            } catch (Exception malformed) {
                parseFailures++;
                continue;
            }
            WebElementAnchorWalkResult walk = WebElementInlineAnchorWalk.walk(root);
            unattributed += walk.unattributed().size();
            for (Map.Entry<String, List<JsonNode>> page : walk.byPage().entrySet()) {
                String pageKey = pageKeys.pageKey(page.getKey());
                for (JsonNode step : page.getValue()) {
                    LinkedHashMap<String, String> anchors =
                            WebElementSurveyCollector.anchorsOf(step);
                    String groupKey = pageKey + "\u0000"
                            + WebElementSurveyCollector.identityOf(anchors);
                    anchorsByGroup.putIfAbsent(groupKey, anchors);
                    scriptsByGroup.computeIfAbsent(groupKey,
                            k -> new LinkedHashSet<>()).add(doc.getKey());
                    stepsByGroup.merge(groupKey, 1, Integer::sum);
                }
            }
        }

        List<WebElementCensusEntry> entries = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<Long>> group : scriptsByGroup.entrySet()) {
            int split = group.getKey().indexOf('\u0000');
            entries.add(new WebElementCensusEntry(
                    group.getKey().substring(0, split),
                    group.getKey().substring(split + 1),
                    anchorsByGroup.get(group.getKey()),
                    new ArrayList<>(group.getValue()),
                    stepsByGroup.get(group.getKey())));
        }
        // Migration value order: scripts first, then raw repetition, stable
        // within ties so the report replays identically on the same inputs.
        List<String> arrival = new ArrayList<>(scriptsByGroup.keySet());
        entries.sort(Comparator
                .comparingInt(WebElementCensusEntry::scriptCount).reversed()
                .thenComparing(Comparator.comparingInt(
                        WebElementCensusEntry::stepCount).reversed())
                .thenComparingInt(e -> arrival.indexOf(e.pageKey() + "\u0000"
                        + e.anchorIdentity())));
        return new WebElementCensusReport(scanned, parseFailures, unattributed, entries);
    }
}

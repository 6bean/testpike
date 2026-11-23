package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Single-source document walk for inline locator steps (WE batch, R40).
 * Two consumers ride it: the save-door candidate registration
 * {@link WebElementGeneratedCandidateLedger} and the S6 census scanner
 * {@link WebElementCensusScanner}. Page attribution = the nearest preceding
 * resolvable goto url in document order (branches inherit the current page);
 * a templated url without an http(s) scheme has no honest page, so its steps
 * land in the unattributed bucket — counted, never guessed.
 */
public final class WebElementInlineAnchorWalk {

    private WebElementInlineAnchorWalk() {
    }

    public static WebElementAnchorWalkResult walk(JsonNode root) {
        LinkedHashMap<String, List<JsonNode>> byPage = new LinkedHashMap<>();
        List<JsonNode> unattributed = new ArrayList<>();
        String pageAfterSetup = collect(root.path("setup"), null, byPage, unattributed);
        collect(root.path("steps"), pageAfterSetup, byPage, unattributed);
        return new WebElementAnchorWalkResult(byPage, unattributed);
    }

    /** True when a step carries at least one locator-dialect anchor. */
    public static boolean hasInlineAnchor(JsonNode step) {
        return step.path("target").isObject()
                && !WebElementSurveyCollector.anchorsOf(step).isEmpty();
    }

    /** Returns the page still current after this block (document order). */
    private static String collect(JsonNode steps, String pageUrl,
                                  LinkedHashMap<String, List<JsonNode>> byPage,
                                  List<JsonNode> unattributed) {
        if (!steps.isArray()) {
            return pageUrl;
        }
        String current = pageUrl;
        for (JsonNode step : steps) {
            JsonNode target = step.path("target");
            if ("goto".equals(step.path("action").asText(""))) {
                String url = target.path("url").asText("").trim();
                current = hasHttpScheme(url) ? url : null;
                continue;
            }
            if (hasInlineAnchor(step)) {
                if (current != null) {
                    byPage.computeIfAbsent(current, k -> new ArrayList<>()).add(step);
                } else {
                    unattributed.add(step);
                }
            }
            collect(step.path("steps"), current, byPage, unattributed);
            collect(step.path("elseSteps"), current, byPage, unattributed);
        }
        return current;
    }

    /** Scheme check mirroring the ledger page-key door without throwing. */
    private static boolean hasHttpScheme(String url) {
        int sep = url.indexOf("://");
        if (sep <= 0) {
            return false;
        }
        String scheme = url.substring(0, sep).toLowerCase();
        return "http".equals(scheme) || "https".equals(scheme);
    }
}

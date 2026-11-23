package com.sixbean.testpike.core.cluster.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Release artifact judgement spectrum (pure functions, single source consumed by
 * the register gate here and the dispatch pre-check/upgrade wizard later — no
 * second verdict copy allowed).
 * <p>Three gates, each failing with a plain-language sentence naming the item:
 * (1) artifact item shape (family / http(s) url / 64-hex sha256);
 * (2) pinned families in the component matrix must have a matching artifact
 * (declaring a pinned version with nothing to download means "promised but
 * can't upgrade" — reject); no pin = announcement decoupled from package
 * supply, allowed;
 * (3) download-host whitelist: every artifact URL host must hit the configured
 * list (plus the mirror host merged at read time). Payload URLs are never
 * followed and hosts outside the list are rejected — SSRF/supply-chain red line.
 */
public final class ReleaseArtifactRules {

    private ReleaseArtifactRules() {
    }

    /** Validate one artifact item; null = acceptable, non-null = human-worded violation. */
    public static String itemViolation(JsonNode item) {
        if (!item.isObject()) {
            return "工件条目必须是对象形态（{component_family,url,sha256,size}）";
        }
        if (item.path("component_family").asText("").isBlank()) {
            return "工件条目缺部件族名（component_family）";
        }
        String url = item.path("url").asText("");
        if (url.isBlank()) {
            return "工件 " + familyOf(item) + " 缺下载地址（url）";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "工件 " + familyOf(item) + " 的下载地址必须是 http/https（当前：" + url + "）";
        }
        String sha = item.path("sha256").asText("");
        if (!sha.matches("[0-9a-fA-F]{64}")) {
            return "工件 " + familyOf(item) + " 缺合法 sha256（64 位十六进制；坏包绝不上的前置校验）";
        }
        return null;
    }

    /** Families declared pinned in the matrix but missing from the artifact list. */
    public static Set<String> pinnedFamiliesWithoutArtifact(JsonNode matrix, JsonNode artifacts) {
        Set<String> supplied = new LinkedHashSet<>();
        for (JsonNode a : artifacts) {
            supplied.add(familyOf(a).toLowerCase(Locale.ROOT));
        }
        Set<String> missing = new LinkedHashSet<>();
        for (JsonNode m : matrix) {
            String family = familyOf(m);
            String pinned = m.path("pinned").asText("");
            if (!pinned.isBlank() && !supplied.contains(family.toLowerCase(Locale.ROOT))) {
                missing.add(family);
            }
        }
        return missing;
    }

    /**
     * Host whitelist verdict: the URL host must equal a listed host or sit under
     * it as a subdomain. Entries tolerate accidental scheme/path noise (hosts
     * extracted, case folded); blank host (no authority) is always rejected.
     */
    public static boolean hostAllowed(JsonNode artifact, Set<String> allowedHosts) {
        String host = hostOf(artifact.path("url").asText(""));
        if (host == null) {
            return false;
        }
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /** Extract the bare host from a config-value/base URL (scheme/path tolerant). */
    public static String hostOf(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String rest = rawUrl.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        rest = rest.trim().toLowerCase(Locale.ROOT);
        return rest.isEmpty() ? null : rest;
    }

    /** Family name of one artifact/matrix item (trimmed, raw case kept for messages). */
    private static String familyOf(JsonNode item) {
        String raw = item.isObject() ? item.path("component_family").asText("") : "";
        if (raw.isBlank() && item.isObject()) {
            raw = item.path("component").asText("");
        }
        return raw.trim();
    }
}

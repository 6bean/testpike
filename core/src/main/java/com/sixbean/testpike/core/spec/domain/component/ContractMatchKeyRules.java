package com.sixbean.testpike.core.spec.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Contract endpoint match key (single source).
 * <p>Layered on top of {@link OpenApiSpecParser#endpointKey} (query stripped, trailing
 * slash removed, upper METHOD), this adds path-segment shape folding: template
 * {@code {petId}}, pure-numeric, UUID and long-hex (>=16) segments all collapse to the
 * {@code {var}} wildcard position. That makes a hand-written concrete request
 * (GET /orders/20261011001) match its contract template endpoint (GET /orders/{id}) —
 * the equality both the stale badge, the inventory joins and the change-impact closure
 * rely on.
 * <p>K8 key {@code spec.path-normalize.rules}: CSV of extra full-segment regexes
 * folded to {var} (hot-updated on every read, no secondary cache; the conservative
 * built-in rules cover the common shapes without any config).
 */
@Component
public class ContractMatchKeyRules {

    /** CSV of extra segment regexes folded to {var} (empty = built-in rules only). */
    public static final String RULES_KEY = "spec.path-normalize.rules";

    private static final String WILDCARD = "{var}";
    private static final Pattern TEMPLATE_SEG = Pattern.compile("\\{[^}]*\\}");
    private static final Pattern NUMERIC_SEG = Pattern.compile("[0-9]+");
    private static final Pattern UUID_SEG = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LONG_HEX_SEG = Pattern.compile("[0-9a-fA-F]{16,}");

    private final ConfigCacheHolder configs;

    public ContractMatchKeyRules(ConfigCacheHolder configs) {
        this.configs = configs;
    }

    /** Match key from raw parts: upper METHOD + folded normalized path. */
    public String matchKey(String method, String rawPath) {
        String canonical = OpenApiSpecParser.endpointKey(method, rawPath);
        return foldEndpointKey(canonical);
    }

    /**
     * Template-shape path alone: canonicalized (query/trailing slash stripped)
     * then segment-folded to {var}. Single fold source reused by the element
     * ledger page keys (WE batch W4) so page assets never fragment across
     * concrete vs template URLs.
     */
    public String foldedPath(String rawPath) {
        return foldPath(OpenApiSpecParser.normalizePath(rawPath));
    }

    /**
     * Match key from a loose endpoint key such as the diff output or a caller-typed
     * {@code "get /pets/?a=1"} — canonicalized (METHOD upper, query/trailing slash
     * stripped) and then segment-folded, same shape as {@link #matchKey}.
     */
    public String matchKeyFromLoose(String endpointKey) {
        String trimmed = endpointKey == null ? "" : endpointKey.trim();
        int sp = trimmed.indexOf(' ');
        String canonical = sp < 0
                ? OpenApiSpecParser.normalizePath(trimmed)
                : OpenApiSpecParser.endpointKey(trimmed.substring(0, sp), trimmed.substring(sp + 1));
        return foldEndpointKey(canonical);
    }

    /** Fold an already-canonical {@code "METHOD /path"} key's path segments. */
    private String foldEndpointKey(String canonicalKey) {
        int sp = canonicalKey.indexOf(' ');
        if (sp < 0) {
            return canonicalKey;
        }
        return canonicalKey.substring(0, sp + 1) + foldPath(canonicalKey.substring(sp + 1));
    }

    /** Segment-wise path folding: every variable-shaped segment becomes {var}. */
    private String foldPath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder(path.length());
        List<Pattern> extras = extraRules();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(foldSegment(segments[i], extras));
        }
        return out.toString();
    }

    private String foldSegment(String seg, List<Pattern> extras) {
        if (seg.isEmpty()) {
            return seg;
        }
        String folded = TEMPLATE_SEG.matcher(seg).replaceAll(WILDCARD);
        if (NUMERIC_SEG.matcher(folded).matches()
                || UUID_SEG.matcher(folded).matches()
                || LONG_HEX_SEG.matcher(folded).matches()) {
            return WILDCARD;
        }
        for (Pattern p : extras) {
            if (p.matcher(folded).matches()) {
                return WILDCARD;
            }
        }
        return folded;
    }

    /** Extra regex rules read fresh per call (K8 hot-update, no secondary cache). */
    private List<Pattern> extraRules() {
        String csv = configs.get(RULES_KEY);
        List<Pattern> patterns = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return patterns;
        }
        for (String raw : csv.split(",")) {
            String regex = raw.trim();
            if (regex.isEmpty()) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(regex));
            } catch (RuntimeException ignored) {
                // A broken rule must not break matching — built-in rules still apply.
            }
        }
        return patterns;
    }
}

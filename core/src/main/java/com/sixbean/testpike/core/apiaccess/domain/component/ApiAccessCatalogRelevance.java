package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Relevance priority for catalog rows that answered a keyword, used when the answer set is larger
 * than the delivery ceiling and a cut has to decide who survives.
 *
 * <p>Why this class exists: the door sorted every keyword hit by entry name and cut the tail, so a
 * the alphabetically-first segment — description echoes — while the entries a human actually
 * registered as answering that word fell off the view. Measured on the live ledger: 224 reachable
 * alone. The cut is the only place where order decides what the dialog model gets to see, so the
 * priority rule lives here as one implementation for every truncated view.
 *
 * <p>What it does not do: it never decides whether a row matches. That stays in the search face
 * (one arbiter, no second copy of the rule), so an entry listed here as low priority can still be
 * delivered in full whenever the view fits under the ceiling — priority only orders, never filters.
 */
public final class ApiAccessCatalogRelevance {

    /** A registered synonym token that IS the keyword: the human-curated direct answer. */
    public static final int TIER_SYNONYM_TOKEN = 0;

    
    public static final int TIER_STRUCTURAL = 1;

    /** Keyword found only in the description prose — an echo, not a nomination. */
    public static final int TIER_DESCRIPTION = 2;

    /** Lowest tier, used when no priority was recorded for a row (blank keyword browse, unregistered entry). */
    public static final int TIER_UNRANKED = TIER_DESCRIPTION;

    /**
     * Synonym separators the corpus registration uses (either language, plus whitespace). A hyphen
     * is deliberately NOT a separator: registered terms are written as whole words
     * ("user-projects"), and splitting them would promote every path fragment into the exact-token
     * tier and dilute the signal that makes the cut trustworthy.
     */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[,，;；、\\s/|]+");

    private ApiAccessCatalogRelevance() {
    }

    /** Registered synonyms of one overlay row as comparable tokens (either language, trimmed). */
    private static List<String> tokens(String corpus) {
        if (corpus == null || corpus.isBlank()) {
            return List.of();
        }
        return TOKEN_SPLIT.splitAsStream(corpus)
                .map(token -> token.trim().toLowerCase(Locale.ROOT))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    /**
     * Priority tier of one catalog row against a keyword (lower sorts earlier).
     *
     * @param needle trimmed lower-cased keyword the search already accepted this row for
     * @param spec   catalog entry being ranked
     * @param row    governance overlay of that entry, null when nothing was registered
     */
    public static int tier(String needle, ApiEndpointSpec spec, ApiAccessGovernanceView row) {
        if (needle == null || needle.isEmpty() || spec == null) {
            return TIER_UNRANKED;
        }
        String corpus = row == null ? null : row.searchTerms();
        if (corpus != null && !corpus.isBlank()) {
            boolean substringHit = false;
            for (String value : tokens(corpus)) {
                if (value.equals(needle)) {
                    return TIER_SYNONYM_TOKEN;
                }
                substringHit = substringHit || value.contains(needle);
            }
            if (substringHit) {
                return TIER_STRUCTURAL;
            }
        }
        if (spec.key().toLowerCase(Locale.ROOT).contains(needle)
                || spec.pathPattern().toLowerCase(Locale.ROOT).contains(needle)) {
            return TIER_STRUCTURAL;
        }
        for (ApiEndpointParam param : spec.params()) {
            if (param.name().toLowerCase(Locale.ROOT).contains(needle)) {
                return TIER_STRUCTURAL;
            }
        }
        return TIER_DESCRIPTION;
    }

    /**
     * Order a matched row set for delivery: priority tier first, entry name inside a tier.
     *
     * <p>Name order within a tier is the shape the door shipped before, so a re-ranked view differs
     * from the old one only in who the cut keeps, never in how the kept rows read.
     *
     * @param rows   matched rows, any order
     * @param tiers  priority per entry key; a key without an entry is treated as {@link #TIER_UNRANKED}
     * @return a new list, the input untouched
     */
    public static List<ApiAccessEndpointView> orderForDelivery(List<ApiAccessEndpointView> rows,
                                                               Map<String, Integer> tiers) {
        List<ApiAccessEndpointView> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator
                .comparingInt((ApiAccessEndpointView view) ->
                        tiers.getOrDefault(view.key(), TIER_UNRANKED))
                .thenComparing(ApiAccessEndpointView::key));
        return ordered;
    }

    /**
     * Priority of one row, recorded while the search loop still has the spec and overlay in hand
     * (the delivered view does not carry them).
     */
    public static void record(Map<String, Integer> tiers, String needle, ApiEndpointSpec spec,
                              ApiAccessGovernanceView row) {
        if (needle == null || needle.isEmpty()) {
            return;
        }
        tiers.put(spec.key(), tier(needle, spec, row));
    }
}

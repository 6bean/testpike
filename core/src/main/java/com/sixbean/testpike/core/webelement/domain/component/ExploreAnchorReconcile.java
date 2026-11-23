package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Exploration diff reconciliation (WE batch S3, : an exploration
 * session records the page's interactive candidate pool (the G4 collection
 * primitive output); this component mechanically compares every confirmed
 * ledger entry of that page against the pool and moves all-miss entries
 * confirmed→stale through the single-source doors — page-key folding
 * (WebElementPageKeys), the lifecycle table and the revision CAS plus audit
 * event (WebElementService.changeState). No model call happens here; the AI
 * re-anchor stage consumes the mismatch set downstream.
 *
 * <p>Honesty pins: the pool serializes only driver-resolvable targets, so
 * xpath/alt/rel anchors are not decidable from it and never fabricate a
 * verdict (an entry with only such anchors is reported not-judgable); a
 * truncated pool means absence-without-evidence, so mismatches are still
 * reported but nothing is marked stale.
 */
@Component
public class ExploreAnchorReconcile {

    /** One anchor's verdict against the recorded candidate pool. */
    public enum Hit { HIT, MISS, NOT_JUDGABLE }

    /** One serialized candidate: tag, visible text and either a css or a text target. */
    public record Candidate(String tag, String text, String cssTarget, String textTarget) {
    }

    /** Parsed pool payload: candidates plus the honesty flag from the collector. */
    public record Pool(List<Candidate> candidates, boolean truncated) {
    }

    /**
     * checked = confirmed rows examined for the page; staleMarked = whether
     * marking actually ran (false on a truncated pool).
     */
    public record Result(int checked, List<Long> matched, List<Long> mismatched,
                         List<Long> notJudgable, boolean staleMarked) {
    }

    private final WebElementService webElementService;
    private final WebElementPageKeys pageKeys;
    private final ObjectMapper mapper;

    public ExploreAnchorReconcile(WebElementService webElementService,
                                  WebElementPageKeys pageKeys,
                                  ObjectMapper mapper) {
        this.webElementService = webElementService;
        this.pageKeys = pageKeys;
        this.mapper = mapper;
    }

    /** Parse the collector payload; malformed payloads refuse with an empty pool. */
    public static Pool parsePool(String poolJson) {
        List<Candidate> candidates = new ArrayList<>();
        boolean truncated = false;
        try {
            JsonNode root = new ObjectMapper().readTree(poolJson == null ? "" : poolJson);
            truncated = root.path("truncated").asBoolean(false);
            for (JsonNode node : root.path("candidates")) {
                JsonNode target = node.path("target");
                candidates.add(new Candidate(
                        node.path("tag").asText(""),
                        node.path("text").asText("").trim(),
                        target.path("css").asText(null),
                        target.path("text").asText(null)));
            }
        } catch (Exception malformed) {
            return new Pool(List.of(), true);
        }
        return new Pool(List.copyOf(candidates), truncated);
    }

    /** Decide one anchor against the pool from the recorded value forms only. */
    public static Hit judge(WebElementAnchorInput anchor, Pool pool) {
        String type = anchor.type();
        String value = anchor.value() == null ? "" : anchor.value().trim();
        if (value.isEmpty()) {
            return Hit.NOT_JUDGABLE;
        }
        for (Candidate cand : pool.candidates()) {
            if (matches(type, value, cand)) {
                return Hit.HIT;
            }
        }
        return switch (type) {
            case "testid", "placeholder", "text", "css" -> Hit.MISS;
            default -> Hit.NOT_JUDGABLE;
        };
    }

    private static boolean matches(String type, String value, Candidate cand) {
        String css = cand.cssTarget();
        return switch (type) {
            case "testid" -> value.equals(attrValue(css, "data-test"))
                    || value.equals(attrValue(css, "data-testid"));
            case "placeholder" -> value.equals(attrValue(css, "placeholder"));
            case "text" -> value.equals(cand.text()) || value.equals(cand.textTarget());
            // Ledger css anchors were validated driver-resolvable forms; the pool
            // emits #id for identifiers so fold the two spellings onto one check.
            case "css" -> value.equals(css)
                    || (value.startsWith("#") && value.substring(1).equals(attrValue(css, "id")));
            default -> false;
        };
    }

    /** Read [attr="value"] (single quotes or bare too) from a collected css target. */
    private static String attrValue(String css, String attr) {
        if (css == null || !css.startsWith("[" + attr)) {
            return null;
        }
        int eq = css.indexOf('=', attr.length() + 1);
        if (eq < 0) {
            return null;
        }
        String rest = css.substring(eq + 1).trim();
        char quote = rest.charAt(0);
        if (quote == '"' || quote == '\'') {
            int close = rest.indexOf(quote, 1);
            return close < 0 ? null : rest.substring(1, close);
        }
        int close = rest.indexOf(']');
        return close < 0 ? rest : rest.substring(0, close);
    }

    /**
     * Compare every confirmed entry of the page and mark all-miss entries stale
     * (skipped on a truncated pool). Returns the three-way verdict sets for the
     * downstream re-anchor stage and the drill-down report.
     */
    public Result reconcile(long projectId, String pageUrl, String poolJson) {
        Pool pool = parsePool(poolJson);
        String pageKey = pageKeys.pageKey(pageUrl);
        List<WebElementView> confirmed = webElementService.listByProject(projectId, pageKey).stream()
                .filter(row -> row.state() == WebElementLifecycle.CONFIRMED)
                .toList();
        List<Long> matched = new ArrayList<>();
        List<Long> mismatched = new ArrayList<>();
        List<Long> notJudgable = new ArrayList<>();
        boolean marked = false;
        for (WebElementView row : confirmed) {
            Verdict verdict = verdictOf(row, pool);
            switch (verdict) {
                case HIT -> matched.add(row.id());
                case MISMATCH -> {
                    mismatched.add(row.id());
                    if (!pool.truncated()) {
                        WebElementView fresh = webElementService.findById(row.id());
                        if (fresh.state() == WebElementLifecycle.CONFIRMED) {
                            webElementService.changeState(fresh.id(),
                                    WebElementLifecycle.STALE, fresh.revision());
                            marked = true;
                        }
                    }
                }
                case NOT_JUDGABLE -> notJudgable.add(row.id());
            }
        }
        return new Result(confirmed.size(), List.copyOf(matched), List.copyOf(mismatched),
                List.copyOf(notJudgable), marked);
    }

    private enum Verdict { HIT, MISMATCH, NOT_JUDGABLE }

    /** Entry-level verdict: any anchor hit wins; all-miss needs one judgable anchor. */
    private static Verdict verdictOf(WebElementView row, Pool pool) {
        boolean anyJudgable = false;
        for (WebElementAnchorInput anchor : row.anchors()) {
            Hit hit = judge(anchor, pool);
            if (hit == Hit.HIT) {
                return Verdict.HIT;
            }
            if (hit == Hit.MISS) {
                anyJudgable = true;
            }
        }
        return anyJudgable ? Verdict.MISMATCH : Verdict.NOT_JUDGABLE;
    }
}

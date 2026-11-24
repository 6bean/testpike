package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementVariantInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Full-page survey outcome ingestion (WE batch S4, segment P2; design §A5 +
 * §8.2): the manual "survey this page" task rides the SAME exploration
 * channel (task_type=3), and its journal step carries the G4 candidate pool
 * re-collected per viewport class. This component turns that pool into
 * CANDIDATE ledger rows — every write goes through the single-source doors of
 * {@link WebElementService} (create / updateAnchors / updateVariants, each
 * consuming the revision CAS token), never a raw write.
 *
 * <p>Policy pins (honest boundaries):
 * <ul>
 * <li>survey evidence is OBSERVATION, not execution proof, so rows enter as
 * candidates and human approval stays the only door into confirmed;
 * confirmed and stale rows hit by the survey are kept untouched (no
 * silent demotion, no anchor rewrite behind the reviewer's back);</li>
 * <li>retired rows are terminal and never resurrect;</li>
 * <li>the same element seen across viewport classes stays ONE row: its
 * default-tier anchors travel on top, the tier anchor sets travel in
 * {@code variants}; tiers with no default-tier twin contribute their
 * own anchors as the row's fallback set;</li>
 * <li>replaying the identical pool writes zero rows and zero audit events
 * (idempotent by anchor-set and variant-set comparison);</li>
 * <li>a malformed or locator-less pool fails soft to an empty outcome —
 * one bad survey never bounces the step journal.</li>
 * </ul>
 */
@Component
public class WebElementSurveyCollector {

    /**
 * Single-source marker prefix of survey goals: the dispatch side (segment
 * P2b) files explore sessions whose goal starts with this prefix, and the
 * journal relay consults {@link #isSurveyGoal} before ingesting, so free
 * explorations never mass-create candidates from incidental pools.
 */
    public static final String SURVEY_GOAL_PREFIX = "整页测绘：";

    /**
 * Whole-site survey goal marker : the dispatch door
 * prefixes profile-driven sessions with this (followed by the profile id
 * and the start URL) so the step relay can route process frames onto the
 * dedicated site kind while candidate pools still flow through this one
 * ingest source — routing split, never a second INSERT path.
 */
    public static final String SITE_SURVEY_GOAL_PREFIX =
            com.sixbean.testpike.common.util.ExploreEnvelopeContract.SITE_SURVEY_GOAL_PREFIX;

    /** Legacy anchor note of the survey door (byte-stable for old rows). */
    private static final String DEFAULT_ORIGIN_NOTE = "整页测绘自动登记";

    /** Locator dialects the ledger accepts (mirrors the service value domain). */
    private static final Set<String> LOCATOR_DIALECTS =
            Set.of("css", "xpath", "text", "placeholder", "alt", "testid", "rel");

    /** Evidence of the survey step behind the written anchors. */
    public record Evidence(Long sessionId, String screenshotKey, String domSnapshotKey) {
    }

    /**
     * What the ledger door did for one observed element, plus the business
     * wording that frame carries on screen. The label lives here so every
     * surface reading an outcome (tail drawer frame, replay note) shows the
     * same words instead of spelling out the enum constant.
     */
    public enum Action {
        /** New row entered through the create door as a candidate. */
        CREATED("新增元素"),
        /** Candidate row gained missing anchors (plus merged variants). */
        ANCHORS_EXTENDED("补充定位"),
        /** Row already carries this anchor set and variant set: zero writes. */
        REPLAYED("重复跳过"),
        /** Confirmed or stale row kept untouched — observation never rewrites assets. */
        STATE_KEPT("保留已有"),
        /** Retired row (terminal): left as-is, never resurrected. */
        RETIRED_SKIPPED("跳过已下线");

        private final String screenLabel;

        Action(String screenLabel) {
            this.screenLabel = screenLabel;
        }

        /** Wording shown to the operator for this outcome (never the constant name). */
        public String screenLabel() {
            return screenLabel;
        }
    }

    public record ItemOutcome(Action action, Long elementId, String name) {
    }

    /** One merged element: default-tier anchors plus per-viewport tier sets. */
    private static final class Observed {
        String name;
        final LinkedHashMap<String, String> anchors = new LinkedHashMap<>();
        final LinkedHashMap<String, LinkedHashMap<String, String>> tiers = new LinkedHashMap<>();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebElementService ledger;
    private final WebElementPageKeys pageKeys;

    public WebElementSurveyCollector(WebElementService ledger,
                                     WebElementPageKeys pageKeys) {
        this.ledger = ledger;
        this.pageKeys = pageKeys;
    }

    /** True when the goal carries the whole-site survey marker ( door;
 * predicate single-sourced in ExploreEnvelopeContract — the ai-service
 * kernel branch is the second consumer, one word list, no copy). */
    public static boolean isSiteSurveyGoal(String goalText) {
        return com.sixbean.testpike.common.util.ExploreEnvelopeContract
                .isSiteSurveyGoal(goalText);
    }

    /**
     * True when the explore goal carries either survey dialect (single-page
     * or whole-site): the ingest gate accepts BOTH so per-page candidate
     * pools keep flowing through this one collector source; the routing to
     * the site process-stream kind uses {@link #isSiteSurveyGoal}.
     */
    public static boolean isSurveyGoal(String goalText) {
        String goal = goalText == null ? null : goalText.trim();
        return goal != null
                && (goal.startsWith(SURVEY_GOAL_PREFIX) || goal.startsWith(SITE_SURVEY_GOAL_PREFIX));
    }

    /** Ingest one survey candidate pool into the page ledger; see class javadoc. */
    public List<ItemOutcome> survey(long projectId, String pageUrl, String poolJson,
                                    Evidence evidence) {
        return survey(projectId, pageUrl, poolJson, evidence, DEFAULT_ORIGIN_NOTE);
    }

    /**
     * Ingest a candidate pool with an explicit origin note on every anchor
     * (WE batch S5 piece D: the AI save door registers invented locators
     * through this same idempotent upsert, and the approval panel must tell
     * survey-observed anchors from generation-invented ones). The 4-arg door
     * above keeps the legacy survey note byte-for-byte.
     */
    public List<ItemOutcome> survey(long projectId, String pageUrl, String poolJson,
                                    Evidence evidence, String originNote) {
        List<Observed> items = parse(poolJson);
        if (items.isEmpty()) {
            return List.of();
        }
        String pageKey = pageKeys.pageKey(pageUrl);
        List<WebElementView> rows = new ArrayList<>(ledger.listByProject(projectId, pageKey));
        List<ItemOutcome> outcomes = new ArrayList<>();
        for (Observed item : items) {
            outcomes.add(upsertOne(projectId, pageUrl, rows, item, evidence, originNote));
        }
        return outcomes;
    }

    private ItemOutcome upsertOne(long projectId, String pageUrl, List<WebElementView> rows,
                                  Observed item, Evidence evidence, String originNote) {
        WebElementView hit = null;
        for (WebElementView row : rows) {
            if (sharesAnchor(row, item)) {
                hit = row;
                break;
            }
        }
        if (hit == null) {
            for (WebElementView row : rows) {
                if (hit == null && row.name() != null && row.name().equals(item.name)) {
                    hit = row;
                }
            }
        }
        if (hit == null) {
            WebElementView created = ledger.create(new WebElementInput(projectId, item.name,
                    pageUrl, anchorInputs(item.anchors, evidence, originNote)));
            WebElementView settled = created;
            List<WebElementVariantInput> variants = variantInputs(item);
            if (!variants.isEmpty()) {
                settled = ledger.updateVariants(created.id(), variants, created.revision());
            }
            rows.add(settled);
            return new ItemOutcome(Action.CREATED, settled.id(), item.name);
        }
        if (hit.state() == WebElementLifecycle.RETIRED) {
            return new ItemOutcome(Action.RETIRED_SKIPPED, hit.id(), item.name);
        }
        if (hit.state() != WebElementLifecycle.CANDIDATE) {
            return new ItemOutcome(Action.STATE_KEPT, hit.id(), item.name);
        }
        List<WebElementAnchorInput> merged = new ArrayList<>(hit.anchors());
        for (Map.Entry<String, String> anchor : item.anchors.entrySet()) {
            if (!hasAnchor(merged, anchor.getKey(), anchor.getValue())) {
                merged.add(new WebElementAnchorInput(anchor.getKey(), anchor.getValue(),
                        WebElementDispatchSnapshot.structuralPrior(anchor.getKey()),
                        evidenceNote(evidence, originNote)));
            }
        }
        List<WebElementVariantInput> target = mergeVariants(hit.variants(), item);
        if (merged.size() == hit.anchors().size() && sameVariants(target, hit.variants())) {
            return new ItemOutcome(Action.REPLAYED, hit.id(), item.name);
        }
        WebElementView updated = ledger.updateAnchors(hit.id(), merged, hit.revision());
        WebElementView settled = updated;
        if (!sameVariants(target, updated.variants())) {
            settled = ledger.updateVariants(updated.id(), target, updated.revision());
        }
        replace(rows, settled);
        return new ItemOutcome(Action.ANCHORS_EXTENDED, settled.id(), item.name);
    }

    private static void replace(List<WebElementView> rows, WebElementView fresh) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(fresh.id())) {
                rows.set(i, fresh);
                return;
            }
        }
        rows.add(fresh);
    }

    /** Merge same element identities (or same label) across viewport tiers. */
    static List<Observed> parse(String poolJson) {
        List<Observed> items = new ArrayList<>();
        if (poolJson == null || poolJson.isBlank()) {
            return items;
        }
        try {
            JsonNode root = MAPPER.readTree(poolJson);
            readTier(root.path("candidates"), null, items);
            for (JsonNode tier : root.path("variants")) {
                String viewport = tier.path("viewport").asText("").trim().toLowerCase();
                if (!viewport.isEmpty()) {
                    readTier(tier.path("candidates"), viewport, items);
                }
            }
        } catch (Exception malformed) {
            return new ArrayList<>();
        }
        return items;
    }

    private static void readTier(JsonNode candidates, String viewport, List<Observed> items) {
        if (!candidates.isArray()) {
            return;
        }
        for (JsonNode node : candidates) {
            LinkedHashMap<String, String> anchors = anchorsOf(node);
            if (anchors.isEmpty()) {
                continue;
            }
            String identity = identityOf(anchors);
            String label = nameOf(node, anchors);
            Observed found = null;
            for (Observed item : items) {
                if (identityOf(item.anchors).equals(identity) || item.name.equals(label)) {
                    found = item;
                    break;
                }
            }
            if (found == null) {
                found = new Observed();
                found.name = label;
                items.add(found);
            }
            found.anchors.putAll(anchors);
            if (viewport != null) {
                found.tiers.computeIfAbsent(viewport, k -> new LinkedHashMap<>())
                        .putAll(anchors);
            }
        }
    }

    static LinkedHashMap<String, String> anchorsOf(JsonNode node) {
        LinkedHashMap<String, String> anchors = new LinkedHashMap<>();
        JsonNode target = node.path("target");
        if (!target.isObject()) {
            return anchors;
        }
        target.fields().forEachRemaining(entry -> {
            String dialect = entry.getKey().toLowerCase();
            if (LOCATOR_DIALECTS.contains(dialect) && entry.getValue().isTextual()
                    && !entry.getValue().asText().isBlank()) {
                anchors.put(dialect, entry.getValue().asText().trim());
            }
        });
        return anchors;
    }

    private static String nameOf(JsonNode node, LinkedHashMap<String, String> anchors) {
        String label = node.path("name").asText("").trim();
        if (label.isEmpty()) {
            label = node.path("text").asText("").trim();
        }
        if (label.isEmpty()) {
            Map.Entry<String, String> first = anchors.entrySet().iterator().next();
            label = first.getKey() + ":" + first.getValue();
        }
        return label;
    }

    static String identityOf(LinkedHashMap<String, String> anchors) {
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, String> anchor : anchors.entrySet()) {
            key.append(anchor.getKey()).append('=').append(anchor.getValue()).append('|');
        }
        return key.toString();
    }

    private static boolean sharesAnchor(WebElementView row, Observed item) {
        for (Map.Entry<String, String> anchor : item.anchors.entrySet()) {
            if (hasAnchor(row.anchors(), anchor.getKey(), anchor.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnchor(List<WebElementAnchorInput> anchors, String type,
                                     String value) {
        for (WebElementAnchorInput anchor : anchors) {
            if (anchor.type() != null && anchor.value() != null
                    && anchor.type().equalsIgnoreCase(type)
                    && anchor.value().trim().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
 * Ledger-shaped anchor list from a survey's type→value map (WE-S6
 * census migration, piece 3). The strength prior and note wording
 * ride the same single-source {@link #anchorInputs} fold the survey
 * batch itself uses, so a migrated card and a surveyed candidate can
 * never disagree on what an anchor is worth.
 */
    public static List<WebElementAnchorInput> ledgerAnchorInputs(
            LinkedHashMap<String, String> anchors, String originNote) {
        return anchorInputs(anchors, null, originNote);
    }

    private static List<WebElementAnchorInput> anchorInputs(
            LinkedHashMap<String, String> anchors, Evidence evidence, String originNote) {
        List<WebElementAnchorInput> inputs = new ArrayList<>();
        for (Map.Entry<String, String> anchor : anchors.entrySet()) {
            inputs.add(new WebElementAnchorInput(anchor.getKey(), anchor.getValue(),
                    WebElementDispatchSnapshot.structuralPrior(anchor.getKey()),
                    evidenceNote(evidence, originNote)));
        }
        return inputs;
    }

    private static String evidenceNote(Evidence evidence, String originNote) {
        StringBuilder note = new StringBuilder(originNote);
        if (evidence == null) {
            return note.toString();
        }
        if (evidence.sessionId() != null) {
            note.append(" sessionId=").append(evidence.sessionId());
        }
        if (evidence.screenshotKey() != null && !evidence.screenshotKey().isBlank()) {
            note.append(" screenshotKey=").append(evidence.screenshotKey());
        }
        if (evidence.domSnapshotKey() != null && !evidence.domSnapshotKey().isBlank()) {
            note.append(" domSnapshotKey=").append(evidence.domSnapshotKey());
        }
        return note.toString();
    }

    private static List<WebElementVariantInput> variantInputs(Observed item) {
        List<WebElementVariantInput> variants = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> tier : item.tiers.entrySet()) {
            variants.add(new WebElementVariantInput(tier.getKey(),
                    anchorInputs(tier.getValue(), null, DEFAULT_ORIGIN_NOTE)));
        }
        return variants;
    }

    private static List<WebElementVariantInput> mergeVariants(
            List<WebElementVariantInput> current, Observed item) {
        LinkedHashMap<String, LinkedHashMap<String, String>> byViewport = new LinkedHashMap<>();
        for (WebElementVariantInput view : current) {
            LinkedHashMap<String, String> set = new LinkedHashMap<>();
            for (WebElementAnchorInput anchor : view.anchors()) {
                if (anchor.type() != null && anchor.value() != null) {
                    set.put(anchor.type().trim().toLowerCase(), anchor.value().trim());
                }
            }
            byViewport.put(view.viewport(), set);
        }
        for (Map.Entry<String, LinkedHashMap<String, String>> tier : item.tiers.entrySet()) {
            byViewport.computeIfAbsent(tier.getKey(), k -> new LinkedHashMap<>())
                    .putAll(tier.getValue());
        }
        List<WebElementVariantInput> merged = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : byViewport.entrySet()) {
            merged.add(new WebElementVariantInput(entry.getKey(),
                    anchorInputs(entry.getValue(), null, DEFAULT_ORIGIN_NOTE)));
        }
        return merged;
    }

    private static boolean sameVariants(List<WebElementVariantInput> left,
                                         List<WebElementVariantInput> right) {
        return normalizeVariants(left).equals(normalizeVariants(right));
    }

    private static Map<String, java.util.Set<String>> normalizeVariants(
            List<WebElementVariantInput> list) {
        Map<String, java.util.Set<String>> shape = new TreeMap<>();
        for (WebElementVariantInput item : list) {
            java.util.Set<String> anchors = new java.util.TreeSet<>();
            for (WebElementAnchorInput anchor : item.anchors()) {
                anchors.add(String.valueOf(anchor.type()).trim().toLowerCase() + "="
                        + String.valueOf(anchor.value()).trim());
            }
            shape.put(String.valueOf(item.viewport()).trim().toLowerCase(), anchors);
        }
        return shape;
    }
}

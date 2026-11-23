package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.webelement.api.dto.ElementAnchorsClaimBinding;
import com.sixbean.testpike.core.webelement.api.dto.ElementSnapshot;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementVariantInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Assembly-side reference normalisation and dispatch snapshot (WE batch S1).
 * The claim path asks this component exactly once per browser task:
 * <ol>
 *   <li>string placeholder targets {@code {{ELEM:name}}} are rewritten to the
 *       object form {@code {"by":"element","name":name}} — the single
 *       normalisation point; inline targets and reference-free documents return
 *       the original string untouched (trailing zero-drift discipline);</li>
 *   <li>every referenced name is resolved against the element ledger in ONE
 *       batched read (one dispatch, one query); confirmed rows travel by
 *       default, candidate rows only when the action JSON explicitly declares
 *       {@code "elementAnchors":{"allowCandidate":true}} (draft trial run);
 *       stale/retired rows never qualify;</li>
 *   <li>anchors are ranked here so the executor only tries them in order
 *       (design §2.3 ordering formula = strength x feedback confidence, this
 *       class is its single source; viewport-class selection falls back to the
 *       top-level anchor set until the environment profile declares a class —
 *       §8.2 fallback rule).</li>
 * </ol>
 * Fail-safe discipline is shared with the sessionState declaration support:
 * dirty JSON or non-browser tasks pass through and never break a claim round.
 */
@Component
public class WebElementDispatchSnapshot {

    private static final int TASK_TYPE_CASE_EXECUTION = 1;
    private static final String BY_ELEMENT = "element";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{\\{ELEM:(.+)\\}\\}$");

    /** Structural prior for anchors without an explicit strength (design §2.3). */
    private static final Map<String, Double> TYPE_PRIOR = Map.of(
            "testid", 0.95,
            "role", 0.95,
            "label", 0.8,
            "placeholder", 0.8,
            "text", 0.75,
            "css", 0.6,
            "xpath", 0.5,
            "rel", 0.3);
    private static final double UNKNOWN_TYPE_PRIOR = 0.4;

    /**
     * Single source of the anchor structural prior (design §2.3): the dispatch
     * scoring default and the re-anchor ticket strength both read this map, so
     * a ticket anchor weighs exactly what a null-strength anchor already weighs
     * here (second consumer rule, no copied table).
     */
    static double structuralPrior(String type) {
        return TYPE_PRIOR.getOrDefault(type, UNKNOWN_TYPE_PRIOR);
    }

    /**
     * Direct-re-anchor floor fallback (design §8.1). Matches the seeded config
     * value; an illegal or missing config row must not break a claim round
     * (fail-safe: suggestion route still needs the floor, never a crash).
     */
    static final double DEFAULT_RE_ANCHOR_SIM_FLOOR = 0.85;
    static final String RE_ANCHOR_FLOOR_KEY = "element.re-anchor.similarity-floor";

    private final ObjectMapper mapper;
    private final WebElementService webElementService;
    private final ConfigService configService;

    public WebElementDispatchSnapshot(ObjectMapper mapper, WebElementService webElementService,
                                      ConfigService configService) {
        this.mapper = mapper;
        this.webElementService = webElementService;
        this.configService = configService;
    }

    /**
     * Result of one assembly pass: the (possibly normalised) action document —
     * byte-identical to the input when nothing referenced the ledger — plus
     * the trailing envelope binding (null = key not emitted).
     */
    public record Envelope(String actionJson, ElementAnchorsClaimBinding anchors) {
    }

    public Envelope assemble(long projectId, int taskType, String actionJson) {
        if (taskType != TASK_TYPE_CASE_EXECUTION || actionJson == null || actionJson.isBlank()) {
            return new Envelope(actionJson, null);
        }
        JsonNode root;
        try {
            root = mapper.readTree(actionJson);
        } catch (Exception dirtyJson) {
            return new Envelope(actionJson, null);
        }
        Set<String> names = new LinkedHashSet<>();
        boolean mutated = normaliseReferences(root, names);
        if (names.isEmpty()) {
            return new Envelope(actionJson, null);
        }
        String normalisedJson = actionJson;
        if (mutated) {
            try {
                normalisedJson = mapper.writeValueAsString(root);
            } catch (Exception reSerialiseFailed) {
                normalisedJson = actionJson;
            }
        }
        boolean allowCandidate =
                root.path("elementAnchors").path("allowCandidate").asBoolean(false);
        // §8.2 input-side declaration: an action JSON may name the runtime
        // viewport class (mobile|tablet|desktop). Absent/blank = no claim on
        // any variant, dispatch keeps the top-level anchors (zero drift).
        String viewportClass = root.path("elementAnchors").path("viewportClass").asText(null);
        Map<String, List<ElementSnapshot>> elements = new LinkedHashMap<>();
        names.forEach(name -> elements.put(name, new ArrayList<>()));
        for (WebElementView row
                : webElementService.findForDispatch(projectId, names, allowCandidate)) {
            elements.computeIfAbsent(row.name(), name -> new ArrayList<>())
                    .add(snapshotOf(row, viewportClass));
        }
        elements.replaceAll((name, rows) -> orderRows(rows));
        return new Envelope(normalisedJson, new ElementAnchorsClaimBinding(
                allowCandidate, elements, readReAnchorFloor()));
    }

    /**
     * Hot-read of the direct-re-anchor floor at claim time: the config store
     * is the single source (executor never queries it, red line 6); a blank
     * or non-numeric value degrades to the seeded default 0.85 instead of
     * failing the round.
     */
    private double readReAnchorFloor() {
        String raw = configService.getValue(RE_ANCHOR_FLOOR_KEY,
                String.valueOf(DEFAULT_RE_ANCHOR_SIM_FLOOR));
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed >= 0 && parsed <= 1 ? parsed : DEFAULT_RE_ANCHOR_SIM_FLOOR;
        } catch (RuntimeException unparsable) {
            return DEFAULT_RE_ANCHOR_SIM_FLOOR;
        }
    }

    /**
     * Rewrites textual placeholder targets into the object reference form and
     * collects every referenced name (both forms). Only {@code target} values
     * are rewritten — the placeholder is a locator contract, never a value
     * template, so input texts and URLs keep their own placeholder dialects.
     */
    private boolean normaliseReferences(JsonNode node, Set<String> names) {
        boolean mutated = false;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode target = object.get("target");
            if (target != null && target.isTextual()) {
                Matcher placeholder = PLACEHOLDER.matcher(target.asText());
                if (placeholder.matches()) {
                    String name = placeholder.group(1).trim();
                    if (!name.isEmpty()) {
                        ObjectNode reference = mapper.createObjectNode();
                        reference.put("by", BY_ELEMENT);
                        reference.put("name", name);
                        object.set("target", reference);
                        names.add(name);
                        mutated = true;
                    }
                }
            } else if (target != null && target.isObject()
                    && BY_ELEMENT.equals(target.path("by").asText(null))) {
                String name = target.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                mutated |= normaliseReferences(fields.next().getValue(), names);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                mutated |= normaliseReferences(child, names);
            }
        }
        return mutated;
    }

    /** Snapshot of one ledger row: the class-selected anchor set ordered
     *  strongest first, plus every variant set ordered by the same formula
     *  (design §8.2 — viewport-class selection and multi-anchor ordering share
     *  this component as their single source, the executor never re-scores). */
    private ElementSnapshot snapshotOf(WebElementView row, String viewportClass) {
        return new ElementSnapshot(row.id(), row.pageKey(), row.state(), row.confidence(),
                orderAnchors(selectForViewport(row, viewportClass), row),
                orderVariants(row.variants(), row), row.revision());
    }

    /**
     * §8.2 fallback rule: a declared viewport class resolves to that variant's
     * anchor set when present; a blank or unmatched declaration (and rows with
     * an empty variants array) falls back to the top-level anchors, which stay
     * the any-viewport set — stored single-viewport assets keep their exact
     * pre-variants dispatch shape (zero behaviour drift).
     */
    static List<WebElementAnchorInput> selectForViewport(WebElementView row, String viewportClass) {
        if (viewportClass == null || viewportClass.isBlank()
                || row.variants() == null || row.variants().isEmpty()) {
            return row.anchors();
        }
        String wanted = viewportClass.trim().toLowerCase();
        for (WebElementVariantInput variant : row.variants()) {
            if (variant.viewport() != null && variant.viewport().trim().toLowerCase().equals(wanted)
                    && variant.anchors() != null && !variant.anchors().isEmpty()) {
                return variant.anchors();
            }
        }
        return row.anchors();
    }

    private List<WebElementAnchorInput> orderAnchors(List<WebElementAnchorInput> anchors,
                                                     WebElementView row) {
        List<WebElementAnchorInput> ordered = new ArrayList<>(anchors);
        ordered.sort(Comparator.comparingDouble(
                (WebElementAnchorInput anchor) -> score(anchor, row)).reversed());
        return ordered;
    }

    private List<WebElementVariantInput> orderVariants(List<WebElementVariantInput> variants,
                                                       WebElementView row) {
        List<WebElementVariantInput> ordered = new ArrayList<>();
        if (variants == null) {
            return ordered;
        }
        for (WebElementVariantInput variant : variants) {
            ordered.add(new WebElementVariantInput(variant.viewport(),
                    orderAnchors(variant.anchors() == null ? List.of() : variant.anchors(), row)));
        }
        return ordered;
    }

    private static double score(WebElementAnchorInput anchor, WebElementView row) {
        double strength = anchor.strength() != null ? anchor.strength()
                : structuralPrior(anchor.type());
        return strength * row.confidence();
    }

    /** Confirmed rows first; stable so the batched read order survives ties. */
    private static List<ElementSnapshot> orderRows(List<ElementSnapshot> rows) {
        rows.sort(Comparator.comparingInt(ElementSnapshot::state).reversed());
        return rows;
    }
}

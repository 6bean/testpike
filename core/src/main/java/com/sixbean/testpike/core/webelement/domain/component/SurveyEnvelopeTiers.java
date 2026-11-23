package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Survey viewport-tier envelope attach point (WE-S4 segment P2c, residual
 * surface ③ of P2b): a survey session's element-candidates envelope carries
 * the machine-side tier list read from the install key
 * {@code element.viewport-classes} so the executor replays candidate
 * collection across the declared tiers instead of the default viewport only.
 * <p>Additive contract: the envelope gains ONE optional field; every other
 * envelope (plain-goal sessions, non-candidates actions, malformed JSON,
 * unset key) passes through byte-identical — zero drift on existing action
 * semantics (chain-sensitive surface red line).
 * <p>Single source: the survey marker test is
 * {@link WebElementSurveyCollector#isSurveyGoal} (no second copy), and the
 * tier parsing lives here — {@code WebElementSurveyDispatch}'s human-readable
 * goal declaration consumes the same {@link #declared}.
 */
@Component
public class SurveyEnvelopeTiers {

    /** Install key holding the comma-separated declared viewport classes. */
    public static final String VIEWPORT_CLASSES_KEY = "element.viewport-classes";

    /** Envelope/step field carrying the tier list (executor step key pin). */
    public static final String ENVELOPE_TIERS_FIELD = "viewportClasses";

    private final SystemConfigReader configReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Candidates collection action whose envelope carries the tier field. */
    private static final String CANDIDATES_ACTION = "element-candidates";

    public SurveyEnvelopeTiers(SystemConfigReader configReader) {
        this.configReader = configReader;
    }

    /** Declared tiers from the install key; unset/blank = empty (default tier only). */
    public List<String> declared() {
        String raw = configReader.getString(VIEWPORT_CLASSES_KEY, "");
        List<String> classes = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                classes.add(part.strip());
            }
        }
        return classes;
    }

    /**
 * Site-survey snapshot attach face : the frozen rules
 * snapshot gains the declared tier list as {@code viewportClasses} when it
 * carries none — one parsing source for both survey shapes. Snapshots that
 * already carry the field, blank or unparsable input pass through
 * byte-identical (additive contract, zero drift on existing semantics).
 */
    public String withDeclaredTiers(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return snapshotJson;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(snapshotJson);
            if (!node.isObject() || node.has(ENVELOPE_TIERS_FIELD)) {
                return snapshotJson;
            }
            java.util.List<String> classes = declared();
            if (classes.isEmpty()) {
                return snapshotJson;
            }
            com.fasterxml.jackson.databind.node.ArrayNode tiers =
                    objectMapper.createArrayNode();
            classes.forEach(tiers::add);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                    .set(ENVELOPE_TIERS_FIELD, tiers);
            return objectMapper.writeValueAsString(node);
        } catch (Exception parseFailure) {
            return snapshotJson;
        }
    }

    /**
     * Attach the declared tiers onto a survey session's candidates envelope.
     * Every other shape returns the input string untouched (byte-identical
     * zero drift): plain goal, no declared tiers, other actions, and
     * malformed JSON (shape legality is guarded at the createExploreAct
     * write side — attaching must never mint or swallow those errors).
     */
    public String attach(String actionJson, String goalText) {
        if (!WebElementSurveyCollector.isSurveyGoal(goalText)) {
            return actionJson;
        }
        List<String> tiers = declared();
        if (tiers.isEmpty()) {
            return actionJson;
        }
        try {
            if (actionJson == null || actionJson.isBlank()) {
                return actionJson;
            }
            var node = objectMapper.readTree(actionJson);
            if (node == null || !node.isObject()
                    || !CANDIDATES_ACTION.equals(node.path("action").asText(null))) {
                return actionJson;
            }
            ((ObjectNode) node).putPOJO(ENVELOPE_TIERS_FIELD, tiers);
            return objectMapper.writeValueAsString(node);
        } catch (Exception malformed) {
            return actionJson;
        }
    }
}

package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference-form rewriter (WE-S6 census migration, piece 3b): flips the
 * inline locator target of every step the census attributed to one
 * (page, element) group into the documented {@code {{ELEM:name}}} placeholder
 * dialect — the same stored form the generation chain emits, which the claim
 * path ({@link WebElementDispatchSnapshot}) normalises into the object
 * reference. Page/element attribution rides the shared inline-anchor walk
 * and the survey collector anchor functions, so a slot is flipped exactly
 * when the census counted it (R40: one matching rule for "the same element").
 * <p>Nothing is rewritten twice: slots already naming this element (string
 * placeholder or object reference) are only counted. Malformed documents
 * rewrite to nothing and stay untouched.
 */
@Component
public class WebElementScriptRefRewriter {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{\\{ELEM:(.+)\\}\\}$");

    private final ObjectMapper mapper;
    private final WebElementPageKeys pageKeys;

    public WebElementScriptRefRewriter(ObjectMapper mapper, WebElementPageKeys pageKeys) {
        this.mapper = mapper;
        this.pageKeys = pageKeys;
    }

    /**
     * @return the rewritten document, or {@code null} when no slot of this
     *         element is still inline (already replaced, or not present).
     */
    public String rewrite(String rawJson, String pageKey, String anchorIdentity,
                          String elementName) {
        JsonNode root = parse(rawJson);
        if (root == null || !root.isObject()) {
            return null;
        }
        int replaced = 0;
        for (Map.Entry<String, List<JsonNode>> page
                : WebElementInlineAnchorWalk.walk(root).byPage().entrySet()) {
            if (!pageKeys.pageKey(page.getKey()).equals(pageKey)) {
                continue;
            }
            for (JsonNode step : page.getValue()) {
                LinkedHashMap<String, String> anchors =
                        WebElementSurveyCollector.anchorsOf(step);
                if (anchors.isEmpty() || !WebElementSurveyCollector.identityOf(anchors)
                        .equals(anchorIdentity)) {
                    continue;
                }
                if (step instanceof ObjectNode object) {
                    object.put("target", "{{ELEM:" + elementName + "}}");
                    replaced++;
                }
            }
        }
        return replaced > 0 ? root.toString() : null;
    }

    /** Slots of one document referencing this element by name (card headline). */
    public int countReferences(String rawJson, String elementName) {
        JsonNode root = parse(rawJson);
        return root == null ? 0 : countReferences(root, elementName);
    }

    private JsonNode parse(String rawJson) {
        try {
            return mapper.readTree(rawJson);
        } catch (Exception malformed) {
            return null;
        }
    }

    private int countReferences(JsonNode node, String elementName) {
        int found = 0;
        if (node.isObject()) {
            JsonNode target = node.get("target");
            if (target != null && target.isTextual()) {
                Matcher placeholder = PLACEHOLDER.matcher(target.asText().trim());
                if (placeholder.matches() && placeholder.group(1).trim().equals(elementName)) {
                    found++;
                }
            } else if (target != null && target.isObject()
                    && "element".equals(target.path("by").asText(null))
                    && elementName.equals(target.path("name").asText(null))) {
                found++;
            }
            for (JsonNode child : node) {
                found += countReferences(child, elementName);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                found += countReferences(child, elementName);
            }
        }
        return found;
    }
}

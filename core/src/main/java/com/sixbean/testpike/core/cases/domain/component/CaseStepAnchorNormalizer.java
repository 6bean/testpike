package com.sixbean.testpike.core.cases.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-source normalizer for step-level anchors (tp_case_step.anchor, V624).
 * <p>Why this gate exists: three write chains (AI draft landing, manual edit,
 * script write-back) all persist the anchor column, and each of them
 * re-implementing the shape check would drift on day one (R40). This component
 * is the one place that decides what a legal anchor is:
 * <ul>
 *   <li>NULL / blank = no anchor evidence, stays NULL (legacy and manual rows
 *       are never backfilled);</li>
 *   <li>otherwise a jsonb object whose "kind" is one of the registered dialects
 *       (elem = page element reference, source = requirement document/chunk
 *       evidence, contract = folded contract endpoint key);</li>
 *   <li>an input step carrying no explicit anchor but mentioning the
 *       {{ELEM:name}} dialect in its description gets the element reference
 *       derived mechanically — the dialect stays the single source, this class
 *       only reads it, never invents locator values.</li>
 * </ul>
 * Illegal payloads are refused with the three-element guidance sentence
 * (offending value named + fix entry + correct form example).
 */
@Component
public class CaseStepAnchorNormalizer {

    /** Anchor dialects registered for ; adding one here is the only way in. */
    private static final Set<String> KNOWN_KINDS = Set.of("elem", "source", "contract");
    private static final Pattern ELEM_DIALECT = Pattern.compile("\\{\\{ELEM:([^{}]+)\\}\\}");

    private final ObjectMapper objectMapper;

    public CaseStepAnchorNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Normalize one anchor payload.
     *
     * @param rawAnchor    caller-supplied anchor JSON text; null/blank = none
     * @param description  step description, used to derive the element dialect anchor
     * @return canonical anchor JSON text, or null when there is no anchor evidence
     * @throws ServiceException when the payload is not a legal anchor object
     */
    public String normalize(String rawAnchor, String description) {
        String trimmed = rawAnchor == null ? null : rawAnchor.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return derivedFromDialect(description);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(trimmed);
        } catch (Exception e) {
            throw refuse(trimmed, "不是合法 JSON");
        }
        if (!node.isObject()) {
            throw refuse(trimmed, "必须是 JSON 对象，不能是数组或裸值");
        }
        String kind = node.path("kind").asText("");
        if (!KNOWN_KINDS.contains(kind)) {
            throw refuse(trimmed, "kind 仅支持 elem（页面元素）/ source（需求原文块）/ contract（契约端点折叠键）之一");
        }
        return canonical(trimmed);
    }

    /**
     * Re-serialize anchor text through Jackson so callers compare canonical
     * forms. jsonb::text round-trips from PostgreSQL add spaces after ':' that
     * raw client payloads do not carry — equality on raw strings would falsely
     * report a changed anchor (breaking R28 replay detection).
     */
    public String canonical(String anchorText) {
        if (anchorText == null || anchorText.isBlank()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(anchorText));
        } catch (Exception e) {
            return anchorText;
        }
    }

    /**
     * Derive the element-reference anchor from the {{ELEM:name}} dialect inside a
     * step description; null when the description carries no dialect.
     */
    private String derivedFromDialect(String description) {
        List<String> names = elementRefs(description);
        if (names.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(names.size() == 1
                    ? Map.of("kind", "elem", "ref", names.get(0))
                    : Map.of("kind", "elem", "refs", names));
        } catch (Exception e) {
            // Serializing string values cannot realistically fail; stay
            // conservative and report "no derivable anchor" instead of blocking
            // an otherwise legal step save.
            return null;
        }
    }

    /**
 * Scan one text for {{ELEM:name}} dialect tokens (ordered, distinct).
 * <p>Shared single source for every consumer that reads the dialect out of
 * free text — the description derivation above and the script write-back
 * backfill . This class reads the dialect, it never invents
 * locator values (producer side belongs to the page element ledger).
 */
    public List<String> elementRefs(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher m = ELEM_DIALECT.matcher(text);
        while (m.find()) {
            names.add(m.group(1).trim());
        }
        return List.copyOf(names);
    }

    /**
 * Merge element refs into an existing step anchor ( chain ② merge shape
 * single source): refs are unioned old-then-new, the legacy singular
 * {@code ref} key folds into the ordered list, the requirement-block source
 * evidence keys survive untouched, and the kind label only upgrades from
 * "source" to "elem" once real element refs arrive. An unparsable existing
 * payload is returned canonically unchanged (this merge never rewrites what
 * it cannot read; the write-door validator owns refusals).
 */
    public String mergeElementAnchor(String existingAnchor, List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return canonical(existingAnchor);
        }
        ObjectNode node;
        if (existingAnchor == null || existingAnchor.isBlank()) {
            node = JsonNodeFactory.instance.objectNode();
            node.put("kind", "elem");
        } else {
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(existingAnchor);
            } catch (Exception stale) {
                return canonical(existingAnchor);
            }
            if (!parsed.isObject()) {
                return canonical(existingAnchor);
            }
            node = ((ObjectNode) parsed).deepCopy();
            String kind = node.path("kind").asText("");
            if (kind.isBlank() || "source".equals(kind)) {
                node.put("kind", "elem");
            }
        }
        LinkedHashSet<String> union = new LinkedHashSet<>();
        JsonNode oldRef = node.remove("ref");
        JsonNode oldRefs = node.remove("refs");
        if (oldRef != null && oldRef.isTextual() && !oldRef.asText().isBlank()) {
            union.add(oldRef.asText());
        }
        if (oldRefs != null && oldRefs.isArray()) {
            oldRefs.forEach(r -> {
                if (r.isTextual() && !r.asText().isBlank()) {
                    union.add(r.asText());
                }
            });
        }
        for (String ref : refs) {
            if (ref != null && !ref.isBlank()) {
                union.add(ref.trim());
            }
        }
        if (union.isEmpty()) {
            return canonical(node.toString());
        }
        if (union.size() == 1) {
            node.put("ref", union.iterator().next());
        } else {
            ArrayNode arr = node.putArray("refs");
            union.forEach(arr::add);
        }
        return canonical(node.toString());
    }

    private ServiceException refuse(String offending, String why) {
        return new ServiceException(String.format(
                "步骤锚点（anchor）%s：%s ——请在「用例详情 → 步骤编辑区」或 AI 草案的对应步骤上修正后保存；"
                        + "正确写法示例：{\"kind\":\"elem\",\"ref\":\"login-btn\"}",
                why, shorten(offending)),
                BizErrorCode.VALIDATION_ERROR);
    }

    private static String shorten(String raw) {
        return raw.length() > 60 ? raw.substring(0, 60) + "…" : raw;
    }
}

package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-contained JSON response diff kernel (W-c5 ruling: no third-party diff
 * dependency). Compares two parsed trees leaf-by-leaf and emits four-color
 * entries consumed by the run-history compare view.
 *
 * <p>Semantics pinned deliberately:
 * <ul>
 *   <li>objects compare by key union — a key present on one side only is
 *       ADDED/REMOVED at that exact path (children never double-reported);</li>
 *   <li>arrays compare index-by-index up to the common prefix; the longer
 *       tail reports one ADDED/REMOVED entry per surplus index (a list growing
 *       by one item is one honest entry, not a wholesale replace);</li>
 *   <li>numbers compare by numeric value (1 == 1.0) and never report a mere
 *       literal-formatting change;</li>
 *   <li>a node whose type differs reports TYPE_CHANGED once at that node with
 *       shape-only summaries — descending into a swapped object-vs-array would
 *       bury the real signal under noise;</li>
 *   <li>summaries are bounded so the UI never renders megabytes of payload.</li>
 * </ul>
 *
 * <p>Format tri-state (JSON / text / binary) is NOT this kernel's concern —
 * the read path decides which comparator applies before calling in here.
 */
public final class ResponseDiffKernel {

    /** Upper bound of a scalar summary so diff payloads stay UI-renderable. */
    private static final int MAX_SUMMARY_CHARS = 160;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseDiffKernel() {
    }

    /**
     * Diff two JSON documents given as raw strings.
     *
     * @throws ServiceException when either side is blank or unparsable (the
     *                          caller must route such runs to the text/hash
     *                          fallback, never silently swallow)
     */
    public static List<ResponseDiffEntry> diff(String leftJson, String rightJson) {
        return diffNodes(parse(leftJson, "left"), parse(rightJson, "right"), "$");
    }

    private static JsonNode parse(String raw, String side) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException("响应体 " + side + " 侧为空，无法进行 JSON 结构对比",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ServiceException("响应体 " + side + " 侧不是合法 JSON，无法进行结构对比",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static List<ResponseDiffEntry> diffNodes(JsonNode left, JsonNode right,
                                                     String path) {
        List<ResponseDiffEntry> out = new ArrayList<>();
        walk(left, right, path, out);
        out.sort(Comparator.comparing(ResponseDiffEntry::path));
        return out;
    }

    private static void walk(JsonNode left, JsonNode right, String path,
                             List<ResponseDiffEntry> out) {
        String lt = typeName(left), rt = typeName(right);
        if (!lt.equals(rt)) {
            out.add(new ResponseDiffEntry(path, ResponseDiffKind.TYPE_CHANGED,
                    lt, rt, summary(left), summary(right)));
            return;
        }
        if (left.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            left.fieldNames().forEachRemaining(keys::add);
            right.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode l = left.get(key);
                JsonNode r = right.get(key);
                String child = path + "." + key;
                if (l == null) {
                    out.add(new ResponseDiffEntry(child, ResponseDiffKind.ADDED,
                            "absent", typeName(r), null, summary(r)));
                } else if (r == null) {
                    out.add(new ResponseDiffEntry(child, ResponseDiffKind.REMOVED,
                            typeName(l), "absent", summary(l), null));
                } else {
                    walk(l, r, child, out);
                }
            }
            return;
        }
        if (left.isArray()) {
            int common = Math.min(left.size(), right.size());
            for (int i = 0; i < common; i++) {
                walk(left.get(i), right.get(i), path + "[" + i + "]", out);
            }
            String tailPath = path;
            for (int i = common; i < left.size(); i++) {
                JsonNode surplus = left.get(i);
                out.add(new ResponseDiffEntry(tailPath + "[" + i + "]",
                        ResponseDiffKind.REMOVED, typeName(surplus), "absent",
                        summary(surplus), null));
            }
            for (int i = common; i < right.size(); i++) {
                JsonNode surplus = right.get(i);
                out.add(new ResponseDiffEntry(tailPath + "[" + i + "]",
                        ResponseDiffKind.ADDED, "absent", typeName(surplus),
                        null, summary(surplus)));
            }
            return;
        }
        if (!valuesEqual(left, right)) {
            out.add(new ResponseDiffEntry(path, ResponseDiffKind.VALUE_CHANGED,
                    lt, rt, summary(left), summary(right)));
        }
    }

    /** Numeric-aware equality: 1 and 1.0 are the same value, textual shapes are not. */
    private static boolean valuesEqual(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }

    private static String typeName(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "absent";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNull()) {
            return "null";
        }
        return "string";
    }

    /** Bounded display summary: scalars as text, containers as shape-only. */
    private static String summary(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext() && keys.size() < 6) {
                keys.add(it.next());
            }
            return "object(" + keys.size() + " keys: " + String.join(",", keys) + ")";
        }
        if (node.isArray()) {
            return "array(" + node.size() + ")";
        }
        if (node.isNull()) {
            return "null";
        }
        String text = node.asText();
        if (text.length() <= MAX_SUMMARY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_SUMMARY_CHARS) + "…";
    }
}

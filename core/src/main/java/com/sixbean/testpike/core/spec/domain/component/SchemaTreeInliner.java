package com.sixbean.testpike.core.spec.domain.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inlines {@code $ref} chains of one response schema into a self-contained
 * tree that can be stored inside the tp_api_spec endpoint snapshot.
 * <p>Why at parse time: the stub draft channel replays the stored snapshot
 * and never re-runs the parser, so by then the components section is gone.
 * A snapshot that keeps raw refs would leave the mock side unable to expand
 * the body; a snapshot with refs resolved inline is standalone forever.
 * <p>Honest degradation instead of silence:
 * <ul>
 * <li>a ref already open on the current path becomes
 * {@code {"$cycle": "<ModelName>"}} (models that reference themselves stop
 * recursing and the draft says why);</li>
 * <li>a ref the document cannot resolve becomes
 * {@code {"$unresolved": "<ref>"}};</li>
 * <li>a tree over the node budget becomes {@code {"$truncated": "nodes"}} so
 * a pathological contract cannot blow up the stored row.</li>
 * </ul>
 * allOf branches are merged (later keys win); anyOf/oneOf keep their first
 * variant — a sample body must show one concrete shape, not a union.
 */
public final class SchemaTreeInliner {

    /** Stored-tree guard: node budget per response schema (shared siblings re-walk, cycles do not). */
    static final int MAX_INLINE_NODES = 4096;

    private static final String KEY_REF = "$ref";
    private static final String KEY_CYCLE = "$cycle";
    private static final String KEY_UNRESOLVED = "$unresolved";
    private static final String KEY_TRUNCATED = "$truncated";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_ITEMS = "items";

    private SchemaTreeInliner() {
    }

    /**
     * Deep-copies the schema with every reachable {@code $ref} replaced by its
     * resolved content. Returns null when there is nothing to inline.
     */
    public static Map<String, Object> inline(Map<String, Object> schema,
                                             Map<String, Object> root) {
        if (schema == null) {
            return null;
        }
        int[] budget = {MAX_INLINE_NODES};
        Object inlined = walk(schema, root, new LinkedHashSet<>(), budget);
        return inlined instanceof Map ? asMap(inlined) : null;
    }

    private static Object walk(Object node, Map<String, Object> root,
                               Set<String> openRefs, int[] budget) {
        if (budget[0]-- <= 0) {
            return Map.of(KEY_TRUNCATED, "nodes");
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(walk(item, root, openRefs, budget));
            }
            return out;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return node;
        }
        Map<String, Object> source = asMap(node);
        if (source.get(KEY_REF) instanceof String ref) {
            if (openRefs.contains(ref)) {
                return Map.of(KEY_CYCLE, simpleRefName(ref));
            }
            Map<String, Object> resolved = OpenApiSpecParser.lookupRef(ref, root);
            if (resolved == null) {
                return Map.of(KEY_UNRESOLVED, ref);
            }
            openRefs.add(ref);
            Object inlined = walk(resolved, root, openRefs, budget);
            openRefs.remove(ref);
            return inlined;
        }
        Map<String, Object> out = new LinkedHashMap<>(source);
        mergeCompositions(out, source, root, openRefs, budget);
        if (source.get(KEY_PROPERTIES) instanceof Map<?, ?> props) {
            Map<String, Object> expanded = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : asMap(props).entrySet()) {
                expanded.put(entry.getKey(), walk(entry.getValue(), root, openRefs, budget));
            }
            out.put(KEY_PROPERTIES, expanded);
        }
        if (source.containsKey(KEY_ITEMS)) {
            out.put(KEY_ITEMS, walk(source.get(KEY_ITEMS), root, openRefs, budget));
        }
        return out;
    }

    /** allOf = intersect-everything (merged); anyOf/oneOf = first concrete shape wins. */
    private static void mergeCompositions(Map<String, Object> out, Map<String, Object> source,
                                          Map<String, Object> root, Set<String> openRefs,
                                          int[] budget) {
        if (source.get("allOf") instanceof List<?> branches) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Object branch : branches) {
                Object inlined = walk(branch, root, openRefs, budget);
                if (inlined instanceof Map<?, ?> part) {
                    mergeInto(merged, asMap(part));
                }
            }
            merged.remove("allOf");
            out.putAll(merged);
            out.remove("allOf");
        }
        for (String keyword : List.of("anyOf", "oneOf")) {
            if (source.get(keyword) instanceof List<?> branches && !branches.isEmpty()) {
                Object first = walk(branches.get(0), root, openRefs, budget);
                out.remove(keyword);
                if (first instanceof Map<?, ?> chosen) {
                    mergeInto(out, asMap(chosen));
                }
            }
        }
    }

    private static void mergeInto(Map<String, Object> target, Map<String, Object> addition) {
        for (Map.Entry<String, Object> entry : addition.entrySet()) {
            if (KEY_PROPERTIES.equals(entry.getKey())
                    && entry.getValue() instanceof Map<?, ?> addProps
                    && target.get(KEY_PROPERTIES) instanceof Map<?, ?> baseProps) {
                Map<String, Object> combined = new LinkedHashMap<>(asMap(baseProps));
                combined.putAll(asMap(addProps));
                target.put(KEY_PROPERTIES, combined);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static String simpleRefName(String ref) {
        int cut = ref.lastIndexOf('/');
        return cut >= 0 && cut + 1 < ref.length() ? ref.substring(cut + 1) : ref;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }
}

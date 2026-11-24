package com.sixbean.testpike.common.genkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.util.JsonOutputExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic assembly for the non-UI action family's mechanical gaps
 * <p>Runs outside the deliberation ring, before any budgeted re-issue: the
 * model has no stable concept of this field vocabulary (endpoint does not
 * enforce constrained decoding), while the fixes themselves are anchored
 * facts — a unique project connection name, a url already in the step, an
 * action that takes no locator, a placeholder's legal lexicon. Anything the
 * machine cannot close without guessing is annotated, never invented.
 * <p>Rule sources stay single: this class only mutates the draft; whether a
 * script is contract-valid is decided solely by
 * {@code ActionJsonValidator}/{@code NonUiActionContract} downstream — no
 * second validation semantics here (R40).
 */
public class ScriptMechanicalGapFiller {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Legal ENV/DATA/CRED lexicon: underscore identifier, dots forbidden. */
    private static final Pattern LEXICON_FIXED =
            Pattern.compile("(?:ENV|DATA|CRED):[A-Za-z_][A-Za-z0-9_]*");
    /** Dotted keys of the fixed-lexicon families: repairable by underscore form. */
    private static final Pattern DOTTED_FIXED_KEY =
            Pattern.compile("(ENV|DATA|CRED):([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)");
    /** Actions whose conn field the project connection list can close. */
    private static final List<String> SQL_ACTIONS = List.of("sql.assert", "sql.query");
    /** Narrative fields that are display text, never execution payloads. */
    private static final String FIELD_DESCRIPTION = "description";

    /**
     * @param actionJson     stage-2 script draft (may be prose-wrapped; extraction
     *                       follows the same JsonOutputExtractor seam as the validator)
     * @param connCandidates enabled database connection names resolved for the
     *                       draft's project × environment (names only — values
     *                       and credentials never reach this component)
     */
    public ScriptGapFillResult fill(String actionJson, List<String> connCandidates) {
        JsonNode root;
        try {
            root = MAPPER.readTree(JsonOutputExtractor.extractJson(actionJson));
        } catch (Exception unparsable) {
            return new ScriptGapFillResult(actionJson, List.of());
        }
        if (root == null || !root.isObject()) {
            return new ScriptGapFillResult(actionJson, List.of());
        }
        List<String> notes = new ArrayList<>();
        ObjectNode object = (ObjectNode) root;
        fillStepArray(object, "steps", connCandidates, notes);
        fillStepArray(object, "setup", connCandidates, notes);
        try {
            return new ScriptGapFillResult(MAPPER.writeValueAsString(object), notes);
        } catch (Exception serialization) {
            return new ScriptGapFillResult(actionJson, List.of());
        }
    }

    private void fillStepArray(ObjectNode root, String field, List<String> connCandidates,
                               List<String> notes) {
        JsonNode array = root.path(field);
        if (!array.isArray()) {
            return;
        }
        ArrayNode steps = (ArrayNode) array;
        List<JsonNode> kept = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            JsonNode stepNode = steps.get(i);
            if (!(stepNode instanceof ObjectNode step)) {
                kept.add(stepNode);
                continue;
            }
            String action = step.path("action").asText("");
            normalizePlaceholders(step);
            switch (action) {
                case "console.assert" -> {
                    if (step.has("target")) {
                        step.remove("target");
                        notes.add("第 " + stepNo + " 步 console.assert 不需要定位符，"
                                + "已删除多余的 target（其余字段未动）");
                    }
                    kept.add(step);
                }
                case "net.assert" -> kept.addAll(handleNetAssert(step, stepNo, notes));
                default -> {
                    if (SQL_ACTIONS.contains(action)) {
                        kept.addAll(handleSqlStep(step, stepNo, connCandidates, notes));
                    } else {
                        kept.add(step);
                    }
                }
            }
        }
        steps.removeAll();
        kept.forEach(steps::add);
    }

    /** conn gap closed only by an unambiguous anchor; never by a guess. */
    private List<JsonNode> handleSqlStep(ObjectNode step, int stepNo,
                                         List<String> connCandidates, List<String> notes) {
        String action = step.path("action").asText();
        JsonNode conn = step.get("conn");
        boolean missing = conn == null || conn.isNull() || conn.asText("").isBlank();
        if (!missing) {
            return List.of(step);
        }
        if (connCandidates.size() == 1) {
            step.put("conn", connCandidates.get(0));
            return List.of(step);
        }
        if (connCandidates.isEmpty()) {
            notes.add("第 " + stepNo + " 步 " + action + " 缺 conn，且项目×环境没有启用中的数据库"
                    + "连接，本步数据库校验已跳过（处理办法：先在环境管理中配置数据库连接"
                    + "后重新生成，或人工补写页面断言步骤）");
            return List.of();
        }
        notes.add("第 " + stepNo + " 步 " + action + " 缺 conn，项目×环境存在 "
                + connCandidates.size() + " 个启用连接（候选："
                + String.join("、", connCandidates) + "），无法机械判定，需人工精化："
                + "请在该步 conn 中指定连接名后重新校验");
        return List.of(step);
    }

    /** match derived from a url already in the step, else the step is dropped. */
    private List<JsonNode> handleNetAssert(ObjectNode step, int stepNo, List<String> notes) {
        JsonNode match = step.get("match");
        if (match != null && match.isObject() && !match.isEmpty()) {
            return List.of(step);
        }
        String url = step.path("url").asText("");
        String pattern = deriveUrlPattern(url);
        if (pattern != null) {
            ObjectNode derived = MAPPER.createObjectNode();
            derived.put("urlPattern", pattern);
            step.set("match", derived);
            return List.of(step);
        }
        step.remove("match");
        notes.add("第 " + stepNo + " 步 net.assert 缺 match 且步内无 url 可派生，"
                + "本步网络断言已跳过（处理办法：补写 match.urlPattern 后重新生成）");
        return List.of();
    }

    private String deriveUrlPattern(String url) {
        if (url.isBlank() || !url.startsWith("http")) {
            return null;
        }
        try {
            String path = new java.net.URI(url).getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                String host = new java.net.URI(url).getHost();
                return host == null ? null : "*/" + host + "*";
            }
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            return "*/" + trimmed + "*";
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * Rewrites the repairable dotted ENV/DATA/CRED placeholders inside
     * executable string fields (e.g. {{DATA:brand.code}} → {{DATA:brand_code}});
     * description text and unrepairable shapes are left for the validator.
     */
    private void normalizePlaceholders(ObjectNode step) {
        List<String> fields = new ArrayList<>();
        step.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            if (FIELD_DESCRIPTION.equals(field)) {
                continue;
            }
            JsonNode value = step.get(field);
            if (value != null && value.isTextual()) {
                String normalized = normalizeText(value.asText());
                if (!normalized.equals(value.asText())) {
                    step.put(field, normalized);
                }
            }
        }
    }

    private String normalizeText(String text) {
        Matcher dotted = DOTTED_FIXED_KEY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (dotted.find()) {
            String repaired = dotted.group(1) + ":" + dotted.group(2).replace('.', '_');
            if (LEXICON_FIXED.matcher(repaired).matches()) {
                // The match range excludes the {{ }} wrapper, so emit only the
                // repaired inner token.
                dotted.appendReplacement(out, Matcher.quoteReplacement(repaired));
            } else {
                dotted.appendReplacement(out, Matcher.quoteReplacement(dotted.group()));
            }
        }
        dotted.appendTail(out);
        // Keys that do not even match the repairable lexical shape (e.g.
        // {{DATA:.bad}}) are deliberately left untouched: the validator keeps
        // its own verdict instead of a silent machine rewrite.
        return out.toString();
    }
}

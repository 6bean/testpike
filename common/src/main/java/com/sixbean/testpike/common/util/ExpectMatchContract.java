package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


public final class ExpectMatchContract {

    
    public static final Set<String> MATCH_TYPES = Set.of("contains", "exact", "regex", "range");

    
    public static final Set<String> BOUNDS = Set.of("closed", "open", "left-open", "right-open");

    
    public static final int PATTERN_MAX_LENGTH = 200;

    
    private static final Pattern NESTED_QUANTIFIER =
            Pattern.compile("\\([^()]*[*+][^()]*\\)\\s*[*+]|\\([^()]*\\{[0-9]+,?\\d*\\}[^()]*\\)\\s*[*+{]");

    private ExpectMatchContract() {
    }

    
    public static String expectViolation(JsonNode step) {
        JsonNode matchType = step.get("matchType");
        if (matchType == null || matchType.isNull() || matchType.asText().isBlank()) {
            return null;
        }
        String type = matchType.asText();
        String enumViolation = matchTypeViolation(type);
        if (enumViolation != null) {
            return enumViolation;
        }
        return switch (type) {
            case "range" -> rangeViolation(step);
            case "regex" -> regexPatternViolation(step);
            default -> null;
        };
    }

    
    public static String matchTypeViolation(String type) {
        if (MATCH_TYPES.contains(type)) {
            return null;
        }
        return "matchType 仅支持 " + String.join(" / ", matchTypeOptions())
                + "，当前值「" + type + "」";
    }

    
    public static String rangeViolation(JsonNode step) {
        JsonNode min = step.get("min");
        JsonNode max = step.get("max");
        boolean minAbsent = min == null || min.isNull();
        boolean maxAbsent = max == null || max.isNull();
        if (minAbsent && maxAbsent) {
            return "区间断言至少给定 min 或 max 一端";
        }
        String minViolation = numberViolation(min, "min");
        if (minViolation != null) {
            return minViolation;
        }
        String maxViolation = numberViolation(max, "max");
        if (maxViolation != null) {
            return maxViolation;
        }
        JsonNode bounds = step.get("bounds");
        if (bounds != null && !bounds.isNull() && !BOUNDS.contains(bounds.asText())) {
            return "bounds 仅支持 " + String.join(" / ", boundsOptions())
                    + "，当前值「" + bounds.asText() + "」";
        }
        return null;
    }

    
    public static String regexPatternViolation(JsonNode step) {
        JsonNode pattern = step.get("pattern");
        if (pattern == null || pattern.isNull() || pattern.asText().isBlank()) {
            return "正则断言还差匹配模式：请在断言表单填写正则表达式 pattern（例 ^ok$）";
        }
        String value = pattern.asText();
        if (value.length() > PATTERN_MAX_LENGTH) {
            return "正则 pattern 超长：" + value.length() + " 字符，上限 "
                    + PATTERN_MAX_LENGTH;
        }
        if (NESTED_QUANTIFIER.matcher(value).find()) {
            return "正则 pattern 含嵌套量词（如 (a+)+ 形态），已拒——"
                    + "防灾难性回溯拖垮执行器，请改写为非嵌套形态";
        }
        try {
            Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            return "正则 pattern 不可编译：" + value;
        }
        return null;
    }

    
    public static List<String> matchTypeOptions() {
        return MATCH_TYPES.stream().sorted().toList();
    }

    
    public static List<String> boundsOptions() {
        return BOUNDS.stream().sorted().toList();
    }

    private static String numberViolation(JsonNode value, String field) {
        if (value != null && !value.isNull() && !value.isNumber()) {
            return "区间断言的 " + field + " 必须是数值，当前值「" + value.asText() + "」";
        }
        return null;
    }
}

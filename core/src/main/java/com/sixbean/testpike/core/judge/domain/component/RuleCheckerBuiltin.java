package com.sixbean.testpike.core.judge.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.ValidationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


public final class RuleCheckerBuiltin {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^{}]*}}|\\$\\{[^{}]*}");
    private static final List<String> TRUNCATE_TAILS = List.of("…", "...", "⋯", "〔截断〕");

    private RuleCheckerBuiltin() {
    }


    
    public static String emptyOutput(String output, String expected, Map<String, Object> params) {
        if (output.isBlank()) {
            return "被评产出为空（或仅含空白字符）";
        }
        return null;
    }

    
    public static String jsonInvalid(String output, String expected, Map<String, Object> params) {
        if (output.isBlank() || RuleParams.tryParseJson(output) == null) {
            return "产出不是合法 JSON";
        }
        return null;
    }

    
    public static String jsonArray(String output, String expected, Map<String, Object> params) {
        JsonNode node = output.isBlank() ? null : RuleParams.tryParseJson(output);
        if (node == null || !node.isArray()) {
            return "产出不是 JSON 数组";
        }
        int min = RuleParams.intOr(params, "min", 1);
        if (node.size() < min) {
            return "JSON 数组元素数 " + node.size() + " 低于要求 " + min;
        }
        return null;
    }

    
    public static String lengthRange(String output, String expected, Map<String, Object> params) {
        Integer min = RuleParams.intOpt(params, "min");
        Integer max = RuleParams.intOpt(params, "max");
        if (min == null && max == null) {
            throw new ValidationException("规则「length-range」至少配置 min 或 max 之一");
        }
        int len = output.length();
        if (min != null && len < min) {
            return "产出长度 " + len + " 低于下限 " + min;
        }
        if (max != null && len > max) {
            return "产出长度 " + len + " 超过上限 " + max;
        }
        return null;
    }


    
    public static String sensitiveWords(String output, String expected, Map<String, Object> params) {
        List<String> words = RuleParams.stringList(params, "words");
        if (words.isEmpty()) {
            throw new ValidationException("规则「sensitive-words」缺少必填参数 words（敏感词清单）");
        }
        for (String word : words) {
            if (!word.isBlank() && output.contains(word)) {
                return "产出含敏感词「" + word + "」";
            }
        }
        return null;
    }

    
    public static String regexForbidden(String output, String expected, Map<String, Object> params) {
        Pattern p = RuleParams.compile(params, "pattern", "regex-forbidden");
        var m = p.matcher(output);
        if (m.find()) {
            return "产出命中禁用模式「" + truncate(m.group()) + "」";
        }
        return null;
    }

    
    public static String regexRequired(String output, String expected, Map<String, Object> params) {
        Pattern p = RuleParams.compile(params, "pattern", "regex-required");
        if (!p.matcher(output).find()) {
            return "产出缺少必需模式（正则 " + p + " 未命中）";
        }
        return null;
    }

    
    public static String requiredSubstrings(String output, String expected, Map<String, Object> params) {
        List<String> items = RuleParams.stringList(params, "items");
        if (items.isEmpty()) {
            throw new ValidationException("规则「required-substrings」缺少必填参数 items（必需片段清单）");
        }
        List<String> missing = items.stream().filter(s -> !s.isBlank() && !output.contains(s)).toList();
        if (!missing.isEmpty()) {
            return "产出缺少必需片段：" + String.join("、", missing);
        }
        return null;
    }

    
    public static String placeholderLeft(String output, String expected, Map<String, Object> params) {
        var m = PLACEHOLDER.matcher(output);
        if (m.find()) {
            return "产出残留未替换占位符「" + truncate(m.group()) + "」";
        }
        return null;
    }


    
    public static String markdownFenceLeak(String output, String expected, Map<String, Object> params) {
        if (output.contains("```")) {
            return "产出残留 Markdown 代码围栏（```），应提交纯文本/纯 JSON";
        }
        return null;
    }

    
    public static String duplicateLines(String output, String expected, Map<String, Object> params) {
        int minDup = RuleParams.intOr(params, "minDup", 2);
        Map<String, Integer> counts = new HashMap<>();
        for (String line : output.split("\n", -1)) {
            String s = line.strip();
            if (!s.isEmpty()) {
                counts.merge(s, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= minDup) {
                return "行内容重复 " + e.getValue() + " 次（阈值 " + minDup + "）：" + truncate(e.getKey());
            }
        }
        return null;
    }

    
    public static String truncatedTail(String output, String expected, Map<String, Object> params) {
        String tail = output.stripTrailing();
        for (String marker : TRUNCATE_TAILS) {
            if (tail.endsWith(marker)) {
                return "产出以「" + marker + "」收尾，疑似生成被截断";
            }
        }
        return null;
    }

    private static String truncate(String raw) {
        return raw.length() <= 60 ? raw : raw.substring(0, 60) + "…";
    }
}

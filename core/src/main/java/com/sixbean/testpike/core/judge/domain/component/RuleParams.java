package com.sixbean.testpike.core.judge.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ValidationException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


final class RuleParams {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleParams() {
    }

    
    static Integer intOpt(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            throw new ValidationException("规则参数「" + key + "」必须是整数（当前收到=" + raw + "）");
        }
    }

    
    static int intOr(Map<String, Object> params, String key, int defaultValue) {
        Integer v = intOpt(params, key);
        return v == null ? defaultValue : v;
    }

    
    static List<String> stringList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(raw));
    }

    
    static Pattern compile(Map<String, Object> params, String key, String checkerName) {
        Object raw = params == null ? null : params.get(key);
        String pattern = raw == null ? "" : String.valueOf(raw);
        if (pattern.isBlank()) {
            throw new ValidationException("规则「" + checkerName + "」缺少必填参数 " + key + "（正则表达式）");
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new ValidationException("规则「" + checkerName + "」的正则不合法：" + pattern);
        }
    }

    
    static JsonNode tryParseJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }
}

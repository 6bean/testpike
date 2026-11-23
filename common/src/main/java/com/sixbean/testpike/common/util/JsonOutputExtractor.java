package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;


public final class JsonOutputExtractor {

    
    public static String extractJson(String content) {
        if (content == null) {
            return "";
        }
        String stripped = stripMarkerLines(stripFence(content));
        String balanced = firstValidBalancedObject(stripped);
        if (balanced != null) {
            return unwrapMarkerEnvelope(balanced);
        }
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        return unwrapMarkerEnvelope(
                (start >= 0 && end > start) ? stripped.substring(start, end + 1).trim() : stripped);
    }

    
    private static String unwrapMarkerEnvelope(String candidate) {
        if (candidate.isEmpty() || candidate.charAt(0) != '{') {
            return candidate;
        }
        JsonNode root;
        try {
            root = CANDIDATE_VALIDATOR.readTree(candidate);
        } catch (JsonProcessingException e) {
            return candidate;
        }
        if (!root.isObject() || root.size() != 1) {
            return candidate;
        }
        var field = root.fields().next();
        if (!LlmExtractMarkers.ECHO_MARKERS.contains(field.getKey())) {
            return candidate;
        }
        JsonNode value = field.getValue();
        if (value.isObject()) {
            return value.toString();
        }
        if (value.isArray() && value.size() == 1 && value.get(0).isObject()) {
            return value.get(0).toString();
        }
        return candidate;
    }

    
    public static List<int[]> balancedObjectSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        if (text == null) {
            return spans;
        }
        Deque<Integer> open = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                open.push(i);
            } else if (c == '}' && !open.isEmpty()) {
                spans.add(new int[] {open.pop(), i});
            }
        }
        spans.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));
        return spans;
    }

    
    private static final ObjectMapper CANDIDATE_VALIDATOR = new ObjectMapper();

    
    private static String firstValidBalancedObject(String s) {
        for (int[] span : balancedObjectSpans(s)) {
            String candidate = s.substring(span[0], span[1] + 1).trim();
            if (isValidJson(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    
    private static boolean isValidJson(String candidate) {
        try {
            CANDIDATE_VALIDATOR.readTree(candidate);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

 
    private static String stripFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int bodyStart = trimmed.indexOf('\n');
        if (bodyStart < 0) {
            return trimmed;
        }
        String body = trimmed.substring(bodyStart + 1);
        int fenceEnd = body.lastIndexOf("```");
        return (fenceEnd >= 0 ? body.substring(0, fenceEnd) : body).trim();
    }

    
    private static String stripMarkerLines(String content) {
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder(content.length());
        for (String line : lines) {
            if (LlmExtractMarkers.ECHO_MARKERS.contains(line.trim())) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private JsonOutputExtractor() {
    }
}

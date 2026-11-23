package com.sixbean.testpike.aiservice.doc.domain.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <ol>
 * </ol>
 *
 */
public class BoilerplatePruner {

    
    private static final String PAGE_FRACTION = "\\d+\\s*/\\s*\\d+";
    
    private static final String PAGE_CN = "第\\s*\\d+\\s*页(\\s*[/（(]?\\s*共\\s*\\d+\\s*页)?\\s*[)）]?";
    
    private static final String PAGE_EN = "(?i)page\\s+\\d+(\\s+of\\s+\\d+)?";
    
    private static final String HR = "[\\-*_]{3,}";
    
    private static final String TOC_DOT_LEADER = ".*\\.{4,}\\s*\\d*";

    
    static final int HEADER_MAX_CHARS = 40;
    
    static final int MIN_CHUNKS_FOR_CROSS_RULE = 3;
    
    static final double CROSS_OCCUR_RATIO = 0.6;
    
    static final int EDGE_WINDOW_LINES = 2;

    
    public List<Map<String, Object>> prune(List<Map<String, Object>> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        List<String> headerLines = collectCrossBlockHeaders(chunks);
        List<Map<String, Object>> out = new ArrayList<>(chunks.size());
        for (Map<String, Object> chunk : chunks) {
            String kept = pruneText(ContextBudgetPlanner.contentOf(chunk), headerLines);
            Map<String, Object> copy = new java.util.LinkedHashMap<>(chunk);
            copy.put("content", kept);
            out.add(copy);
        }
        return out;
    }

    
    private String pruneText(String content, List<String> headerLines) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        int headEnd = windowHeadEnd(lines.length);
        int tailStart = Math.max(headEnd, lines.length - windowWidth(lines.length));
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (isStructuralLine(trimmed)) {
                continue;
            }
            boolean edgeZone = i < headEnd || i >= tailStart;
            if (edgeZone && headerLines.contains(trimmed) && !trimmed.isEmpty()) {
                continue;
            }
            kept.add(lines[i]);
        }
        return String.join("\n", kept).strip();
    }

 
    private boolean isStructuralLine(String trimmed) {
        if (trimmed.isEmpty()) {
            return false;
        }
        return trimmed.matches(HR)
                || trimmed.matches(PAGE_FRACTION)
                || trimmed.matches(PAGE_CN)
                || trimmed.matches(PAGE_EN)
                || (trimmed.matches(TOC_DOT_LEADER) && trimmed.contains("...."));
    }

 
    private static int windowWidth(int lineCount) {
        return Math.max(1, Math.min(EDGE_WINDOW_LINES, lineCount / 2));
    }

    private static int windowHeadEnd(int lineCount) {
        return Math.min(windowWidth(lineCount), lineCount);
    }

    
    private List<String> collectCrossBlockHeaders(List<Map<String, Object>> chunks) {
        if (chunks.size() < MIN_CHUNKS_FOR_CROSS_RULE) {
            return List.of();
        }
        Map<String, Integer> edgeCounts = new HashMap<>();
        for (Map<String, Object> chunk : chunks) {
            String content = ContextBudgetPlanner.contentOf(chunk);
            if (content.isEmpty()) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            int headEnd = windowHeadEnd(lines.length);
            int tailStart = Math.max(headEnd, lines.length - windowWidth(lines.length));
            java.util.Set<String> chunkEdgeLines = new java.util.HashSet<>();
            for (int i = 0; i < lines.length; i++) {
                if (i < headEnd || i >= tailStart) {
                    String trimmed = lines[i].trim();
                    if (!trimmed.isEmpty() && trimmed.length() <= HEADER_MAX_CHARS) {
                        chunkEdgeLines.add(trimmed);
                    }
                }
            }
            for (String line : chunkEdgeLines) {
                edgeCounts.merge(line, 1, Integer::sum);
            }
        }
        double threshold = Math.ceil(chunks.size() * CROSS_OCCUR_RATIO);
        List<String> headers = new ArrayList<>();
        for (Map.Entry<String, Integer> e : edgeCounts.entrySet()) {
            if (e.getValue() >= threshold) {
                headers.add(e.getKey());
            }
        }
        return headers;
    }
}

package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioItem;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioKind;
import com.sixbean.testpike.common.util.JsonOutputExtractor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage0 scenario list parser : turns the model's enumeration draft
 * into the strict scenario list that feeds the circle-selection step.
 * <p>Contract: the draft is a JSON object {@code {"scenarios":[{kind,title,anchor,note?}]}}
 * (a bare top-level array is also accepted — real providers sometimes drop the
 * envelope). kind must be one of the five adjudicated kinds, title and anchor
 * are mandatory (an unanchored scenario is an invented scope, refused before
 * a human ever sees it), duplicates of the same kind+title pair are dropped.
 * <p>Tolerance mirrors {@link CaseJsonParser}: fenced ```json blocks and
 * surrounding prose go through {@link JsonOutputExtractor} first, so a
 * cosmetically messy but semantically valid draft parses instead of burning
 * a ring round.
 */
public class ScenarioListParser {

    private final ObjectMapper objectMapper;

    public ScenarioListParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ScenarioItem> parse(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("场景清单不是合法 JSON: " + unreadable.getMessage());
        }
        JsonNode scenarios = root.isArray() ? root : root.path("scenarios");
        if (!scenarios.isArray() || scenarios.isEmpty()) {
            throw new IllegalArgumentException("场景清单缺少 scenarios 数组或为空");
        }
        List<ScenarioItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int position = 1;
        for (JsonNode entry : scenarios) {
            String rawKind = text(entry, "kind");
            ScenarioKind kind = ScenarioKind.fromCode(rawKind);
            if (kind == null) {
                throw new IllegalArgumentException("场景第 " + position + " 条的 kind 不合法: " + rawKind);
            }
            String title = text(entry, "title");
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("场景第 " + position + " 条缺少标题(title)");
            }
            String anchor = text(entry, "anchor");
            if (anchor == null || anchor.isBlank()) {
                throw new IllegalArgumentException("场景第 " + position + " 条缺少原文锚点(anchor)："
                        + "每个场景必须能在需求原文里指出依据");
            }
            String note = text(entry, "note");
            if (seen.add(kind.code() + "|" + title.trim())) {
                items.add(new ScenarioItem(kind, title.trim(), anchor.trim(),
                        note == null || note.isBlank() ? null : note.trim()));
            }
            position++;
        }
        return items;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}

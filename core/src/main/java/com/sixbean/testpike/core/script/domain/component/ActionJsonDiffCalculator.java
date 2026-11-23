package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;


public final class ActionJsonDiffCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    
    private static final LinkedHashMap<String, String> FIELD_LABELS = new LinkedHashMap<>();

    static {
        FIELD_LABELS.put("version", "契约版本");
        FIELD_LABELS.put("caseId", "用例编号");
        FIELD_LABELS.put("title", "测试目标");
        FIELD_LABELS.put("endType", "端属性");
        FIELD_LABELS.put("setup", "前置操作");
        FIELD_LABELS.put("steps", "操作步骤");
        FIELD_LABELS.put("env", "执行环境");
        FIELD_LABELS.put("metadata", "附加元数据");
    }

    private ActionJsonDiffCalculator() {
    }

    
    public static List<CaseVersionFieldChange> diff(String fromJson, String toJson) {
        JsonNode from = parseObject(fromJson, "基准");
        JsonNode to = parseObject(toJson, "目标");
        List<CaseVersionFieldChange> changes = new ArrayList<>();
        for (String field : FIELD_LABELS.keySet()) {
            compare(changes, field, from.get(field), to.get(field));
        }
        Stream.concat(streamFieldNames(from), streamFieldNames(to))
                .distinct()
                .filter(f -> !FIELD_LABELS.containsKey(f))
                .sorted()
                .forEach(field -> compare(changes, field, from.get(field), to.get(field)));
        return changes;
    }

    private static Stream<String> streamFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream();
    }

    
    private static void compare(List<CaseVersionFieldChange> changes, String field,
                                JsonNode beforeNode, JsonNode afterNode) {
        JsonNode before = normalize(beforeNode);
        JsonNode after = normalize(afterNode);
        if (Objects.equals(before, after)) {
            return;
        }
        changes.add(new CaseVersionFieldChange(field,
                FIELD_LABELS.getOrDefault(field, field), toJavaValue(before), toJavaValue(after)));
    }

    
    private static JsonNode normalize(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static Object toJavaValue(JsonNode node) {
        return node == null ? null : MAPPER.convertValue(node, Object.class);
    }

 
    private static JsonNode parseObject(String json, String roleLabel) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("%s动作 JSON 内容为空，无法比较".formatted(roleLabel));
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("%s动作 JSON 不是合法 JSON（快照损坏），无法比较".formatted(roleLabel), e);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("%s动作 JSON 不是 JSON 对象（存量格式异常），无法比较".formatted(roleLabel));
        }
        return node;
    }
}

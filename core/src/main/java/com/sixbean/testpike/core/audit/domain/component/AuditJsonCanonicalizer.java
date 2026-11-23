package com.sixbean.testpike.core.audit.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


@Component
public class AuditJsonCanonicalizer {

    private final ObjectMapper mapper;

    public AuditJsonCanonicalizer() {
        this.mapper = new ObjectMapper().findAndRegisterModules();
        this.mapper.configure(
                com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    
    public String toCanonicalJson(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计快照 JSON 序列化失败", e);
        }
    }

    
    public String normalizeJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            Object normalized = normalizeNode(mapper.readTree(json));
            return mapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计 JSON 归一化失败: " + json, e);
        }
    }

    private Object normalizeNode(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), normalizeNode(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(normalizeNode(n)));
            return list;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            BigDecimal stripped = node.decimalValue().stripTrailingZeros();
            return new BigDecimal(stripped.toPlainString());
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.textValue();
    }
}

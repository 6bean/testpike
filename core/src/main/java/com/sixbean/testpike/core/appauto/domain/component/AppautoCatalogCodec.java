package com.sixbean.testpike.core.appauto.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
public class AppautoCatalogCodec {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Boolean>> BOOL_MAP = new TypeReference<>() {
    };

    private static final TypeReference<List<Map<String, String>>> MAP_LIST = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public AppautoCatalogCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<String> readStringList(String json) {
        return read(json, STRING_LIST, List.of());
    }

    
    public Map<String, String> readStringMap(String json) {
        return read(json, STRING_MAP, Map.of());
    }

    
    public Map<String, Boolean> readBoolMap(String json) {
        return read(json, BOOL_MAP, Map.of());
    }

    
    public List<Map<String, String>> readMapList(String json) {
        return read(json, MAP_LIST, List.of());
    }

    
    public Map<String, Object> readObjectMap(String json) {
        return read(json, OBJECT_MAP, Map.of());
    }

    
    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException(
                    "档案内容格式无法保存（请检查键值是否为合法文本/数字/布尔）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }
}

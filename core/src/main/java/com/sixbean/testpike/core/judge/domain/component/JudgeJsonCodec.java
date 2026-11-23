package com.sixbean.testpike.core.judge.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
public class JudgeJsonCodec {

    private static final TypeReference<List<Map<String, Object>>> MAP_LIST =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public JudgeJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new ServiceException("评测数据序列化失败，请检查维度/规则/参考件内容",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, MAP_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    
    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    
    public Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}

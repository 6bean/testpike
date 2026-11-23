package com.sixbean.testpike.core.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


public final class ToolWriteFlag {

    
    public static final String X_WRITE_KEY = "x-write";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolWriteFlag() {
    }

    
    public static boolean resolve(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(schemaJson);
            JsonNode flag = root.get(X_WRITE_KEY);
            if (flag == null) {
                return false;
            }
            return flag.isBoolean() ? flag.booleanValue() : true;
        } catch (Exception e) {
            return true;
        }
    }
}

package com.sixbean.testpike.core.tool.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.ZipPackSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * name/type/category/description/body/tags/inputSchema/outputSchema/execKind/execSpec/
 *
 */
@Component
public class ToolPackSupport {

    
    public static final String CONTRACT_ENTRY = "tool.json";
    
    public static final String BODY_ENTRY = "body.md";
    
    static final int CONTRACT_MAX_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    
    public byte[] buildPack(Map<String, Object> contractFields, String docBody) {
        String json = writeJson(contractFields);
        if (docBody != null && !docBody.isEmpty()) {
            return ZipPackSupport.buildZip(Map.of(
                    CONTRACT_ENTRY, json, BODY_ENTRY, docBody));
        }
        return ZipPackSupport.buildZip(CONTRACT_ENTRY, json);
    }

    
    public ToolCreateInput parseToCreateInput(Long projectId, String filename, byte[] content) {
        String name = filename == null ? "" : filename.toLowerCase();
        String contractJson = name.endsWith(".zip")
                ? extractContract(ZipPackSupport.readEntries(content))
                : new String(content, StandardCharsets.UTF_8);
        Map<String, Object> contract = readContract(contractJson);
        Object nameValue = contract.get("name");
        if (nameValue == null || nameValue.toString().isBlank()) {
            throw new ServiceException("工具契约缺少必填字段 name（工具名称）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new ToolCreateInput(projectId,
                nameValue.toString().trim(),
                asInt(contract.get("type")),
                asText(contract.get("category")),
                asText(contract.get("description")),
                asText(contract.get("body")),
                asJsonText(contract.get("tags")),
                asJsonText(contract.get("inputSchema")),
                asJsonText(contract.get("outputSchema")),
                asInt(contract.get("execKind")),
                asJsonText(contract.get("execSpec")),
                asInt(contract.get("timeoutMs")),
                Boolean.TRUE);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private String extractContract(Map<String, byte[]> entries) {
        byte[] json = entries.get(CONTRACT_ENTRY);
        if (json == null) {
            throw new ServiceException("zip 包内未找到 " + CONTRACT_ENTRY
                    + " 契约文件（工具包需包含与 ToolDefinition 对应的 tool.json）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new String(json, StandardCharsets.UTF_8);
    }

    private Map<String, Object> readContract(String json) {
        if (json == null || json.isBlank()) {
            throw new ServiceException("工具契约（" + CONTRACT_ENTRY + "）内容为空",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > CONTRACT_MAX_BYTES) {
            throw new ServiceException("工具契约（" + CONTRACT_ENTRY + "）超过体积上限 1MB",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (map == null) {
                throw new ServiceException("工具契约（" + CONTRACT_ENTRY + "）需为 JSON 对象",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return map;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("工具契约（" + CONTRACT_ENTRY + "）不是合法 JSON："
                    + e.getMessage(), BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("工具契约字段「" + value + "」不是合法整数",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    
    private String asJsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> || value instanceof Map<?, ?>) {
            return writeJson(value);
        }
        return value.toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException("工具契约序列化失败：" + e.getMessage(),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    public Map<String, Object> contractFields(Object name, Object type, Object category,
                                              Object description, Object body, Object tagsJson,
                                              Object inputSchemaJson, Object outputSchemaJson,
                                              Object execKind, Object execSpecJson,
                                              Object timeoutMs) {
        Map<String, Object> contract = new LinkedHashMap<>();
        putText(contract, "name", name);
        putInt(contract, "type", type);
        putText(contract, "category", category);
        putText(contract, "description", description);
        putText(contract, "body", body);
        putJson(contract, "tags", tagsJson);
        putJson(contract, "inputSchema", inputSchemaJson);
        putJson(contract, "outputSchema", outputSchemaJson);
        putInt(contract, "execKind", execKind);
        putJson(contract, "execSpec", execSpecJson);
        putInt(contract, "timeoutMs", timeoutMs);
        return contract;
    }

    
    public byte[] buildBundleJson(Map<String, Object> contractFields) {
        return writeJson(contractFields).getBytes(StandardCharsets.UTF_8);
    }

    private static void putText(Map<String, Object> contract, String key, Object value) {
        if (value != null) {
            contract.put(key, value.toString());
        }
    }

    private static void putInt(Map<String, Object> contract, String key, Object value) {
        if (value instanceof Number number) {
            contract.put(key, number.intValue());
        }
    }

    
    private void putJson(Map<String, Object> contract, String key, Object jsonText) {
        if (jsonText == null) {
            return;
        }
        try {
            contract.put(key, objectMapper.readTree(jsonText.toString()));
        } catch (Exception e) {
            contract.put(key, jsonText.toString());
        }
    }
}

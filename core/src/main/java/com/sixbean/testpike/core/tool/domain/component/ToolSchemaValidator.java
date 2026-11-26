package com.sixbean.testpike.core.tool.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Set;


@Component
public class ToolSchemaValidator {

    
    private static final String META_SCHEMA_202012 =
            "https://json-schema.org/draft/2020-12/schema";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchema metaSchema;

    public ToolSchemaValidator() {
        this.metaSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of(META_SCHEMA_202012));
    }

    
    public void validateSchema(String fieldLabel, String schemaText) {
        if (schemaText == null || schemaText.isBlank()) {
            return;
        }
        JsonNode node = parse(fieldLabel, schemaText);
        Set<ValidationMessage> errors = metaSchema.validate(node);
        if (!errors.isEmpty()) {
            String first = errors.iterator().next().getMessage();
            throw new ServiceException(fieldLabel + " 不符合 JSON Schema 规范：" + first
                    + "——请前往「工具管理」编辑该工具的「输入 Schema」/「输出 Schema」字段后重新保存"
                    + "（格式: JSON Schema 2020-12，如 {\"type\":\"object\",\"properties\":{}}）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public JsonNode parse(String fieldLabel, String jsonText) {
        try {
            return objectMapper.readTree(jsonText);
        } catch (JsonProcessingException e) {
            throw new ServiceException(fieldLabel + " 不是合法 JSON：" + e.getOriginalMessage()
                    + "——请检查括号、引号与逗号配对后重新保存"
                    + "（格式: 合法 JSON，如 {\"key\":\"value\"}；入口「工具管理」→ 编辑工具）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public boolean sameJson(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a.equals(b)) {
            return true;
        }
        try {
            return objectMapper.readTree(a).equals(objectMapper.readTree(b));
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}

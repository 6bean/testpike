package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.project.domain.component.EnvParamCipherSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class ApiVarPoolSupport {

    private static final Logger log = LoggerFactory.getLogger(ApiVarPoolSupport.class);

    
    public static final String GLOBAL_PREFIX = "global.";

    private final ConfigCipher configCipher;
    private final ObjectMapper objectMapper;

    public ApiVarPoolSupport(ConfigCipher configCipher, ObjectMapper objectMapper) {
        this.configCipher = configCipher;
        this.objectMapper = objectMapper;
    }

    
    public String toStoredJson(Object vars) {
        Map<String, String> plain = toStringMap(vars);
        if (plain.isEmpty()) {
            return "{}";
        }
        ObjectNode node = objectMapper.createObjectNode();
        plain.forEach((key, value) -> {
            String body = key == null ? "" :
                    (key.startsWith(GLOBAL_PREFIX) ? key.substring(GLOBAL_PREFIX.length()) : key);
            if (body.isEmpty() || body.length() > EnvParamCipherSupport.KEY_MAX_LEN
                    || !EnvParamCipherSupport.KEY_PATTERN.matcher(body).matches()) {
                log.warn("变量池键「{}」不合命名规范（字母/下划线开头，可选 global. 前缀），本次不入池", key);
                return;
            }
            node.put(key, encryptValue(value));
        });
        return node.toString();
    }

    
    public String collectionPart(String storedVarsJson) {
        ObjectNode node = objectMapper.createObjectNode();
        poolFields(storedVarsJson).entrySet().stream()
                .filter(e -> !e.getKey().startsWith(GLOBAL_PREFIX))
                .forEach(e -> node.put(e.getKey(), e.getValue()));
        return node.toString();
    }

    
    public Map<String, String> globalPart(String storedVarsJson) {
        Map<String, String> out = new LinkedHashMap<>();
        poolFields(storedVarsJson).forEach((k, v) -> {
            if (k.startsWith(GLOBAL_PREFIX)) {
                out.put(k.substring(GLOBAL_PREFIX.length()), v);
            }
        });
        return out;
    }

    
    private Map<String, String> poolFields(String varsJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (varsJson == null || varsJson.isBlank()) {
            return out;
        }
        try {
            JsonNode pool = objectMapper.readTree(varsJson);
            if (pool.isObject()) {
                pool.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    
    public String encryptValue(String value) {
        return configCipher.isEnabled()
                ? EnvParamCipherSupport.ENC_PREFIX + configCipher.encrypt(value)
                : value;
    }

    
    public String decryptValue(String stored) {
        if (stored == null) {
            return "";
        }
        return stored.startsWith(EnvParamCipherSupport.ENC_PREFIX)
                ? configCipher.decrypt(stored.substring(EnvParamCipherSupport.ENC_PREFIX.length()))
                : stored;
    }

    
    public Map<String, String> decryptStored(String varsJson) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (varsJson == null || varsJson.isBlank()) {
            return vars;
        }
        JsonNode pool;
        try {
            pool = objectMapper.readTree(varsJson);
        } catch (Exception e) {
            return vars;
        }
        if (!pool.isObject()) {
            return vars;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = pool.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String stored = entry.getValue().asText("");
            vars.put(entry.getKey(), stored.startsWith(EnvParamCipherSupport.ENC_PREFIX)
                    ? configCipher.decrypt(stored.substring(EnvParamCipherSupport.ENC_PREFIX.length()))
                    : stored);
        }
        return vars;
    }

    
    private Map<String, String> toStringMap(Object vars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (vars == null) {
            return out;
        }
        try {
            JsonNode node = vars instanceof String s
                    ? (s.isBlank() ? null : objectMapper.readTree(s))
                    : objectMapper.valueToTree(vars);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(e ->
                        out.put(e.getKey(), e.getValue().asText(e.getValue().toString())));
            }
        } catch (Exception e) {
            log.warn("变量池回传载荷非合法对象形，本次不入池: {}", e.getMessage());
        }
        return out;
    }
}

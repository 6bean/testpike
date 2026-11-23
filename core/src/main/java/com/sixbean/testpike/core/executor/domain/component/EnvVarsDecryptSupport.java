package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.project.domain.component.EnvParamCipherSupport;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class EnvVarsDecryptSupport {

    private final EnvParamCipherSupport cipherSupport;
    private final ConfigCipher configCipher;
    private final ObjectMapper objectMapper;

    public EnvVarsDecryptSupport(EnvParamCipherSupport cipherSupport, ConfigCipher configCipher,
                                 ObjectMapper objectMapper) {
        this.cipherSupport = cipherSupport;
        this.configCipher = configCipher;
        this.objectMapper = objectMapper;
    }

    
    public Map<String, String> decryptParams(String configJson) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (configJson == null || configJson.isBlank()) {
            return vars;
        }
        JsonNode params;
        try {
            params = objectMapper.readTree(configJson).path(EnvParamCipherSupport.PARAMS_SECTION);
        } catch (Exception e) {
            return vars;
        }
        if (!params.isObject()) {
            return vars;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = params.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String stored = entry.getValue().asText("");
            vars.put(entry.getKey(), stored.startsWith(EnvParamCipherSupport.ENC_PREFIX)
                    ? configCipher.decrypt(stored.substring(EnvParamCipherSupport.ENC_PREFIX.length()))
                    : stored);
        }
        return vars;
    }
}

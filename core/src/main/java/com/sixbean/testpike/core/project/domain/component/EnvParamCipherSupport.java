package com.sixbean.testpike.core.project.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.project.api.dto.EnvParamItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class EnvParamCipherSupport {

    
    public static final String PARAMS_SECTION = "params";

    
    public static final String ENC_PREFIX = "ENC:";

 
    public static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    
    public static final int KEY_MAX_LEN = 64;

    private final ConfigCipher configCipher;
    private final ObjectMapper objectMapper;

    public EnvParamCipherSupport(ConfigCipher configCipher, ObjectMapper objectMapper) {
        this.configCipher = configCipher;
        this.objectMapper = objectMapper;
    }

    
    public List<EnvParamItem> readItems(String configJson) {
        JsonNode params = readParamsSection(configJson);
        List<EnvParamItem> items = new ArrayList<>();
        params.fields().forEachRemaining(entry -> {
            boolean encrypted = entry.getValue().asText("").startsWith(ENC_PREFIX);
            items.add(new EnvParamItem(entry.getKey(),
                    encrypted ? ConfigCipher.MASK : entry.getValue().asText(""), encrypted));
        });
        return items;
    }

    
    public String mergeParams(String configJson, Map<String, String> submitted) {
        ObjectNode root = readRoot(configJson);
        JsonNode existing = root.path(PARAMS_SECTION);
        List<String> invalidKeys = new ArrayList<>();
        if (submitted != null) {
            for (Map.Entry<String, String> entry : submitted.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.length() > KEY_MAX_LEN || !KEY_PATTERN.matcher(key).matches()) {
                    invalidKeys.add(String.valueOf(key));
                    continue;
                }
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    throw new ServiceException("参数 " + key + " 的值不能为空，请在项目详情「环境配置」的「环境参数」中填写实际值后保存", BizErrorCode.VALIDATION_ERROR);
                }
                if (value.startsWith(ENC_PREFIX)) {
                    throw new ServiceException("参数 " + key + " 的值不能以 " + ENC_PREFIX
                            + " 开头（密文形态由系统生成，禁止伪装提交），请直接填写真实明文，系统保存时自动加密（入口：项目详情「环境配置」→「环境参数」）", BizErrorCode.VALIDATION_ERROR);
                }
                if (ConfigCipher.MASK.equals(value) && !isEncryptedExisting(existing, key)) {
                    throw new ServiceException("参数 " + key + " 提交了打码占位值但原键无可保留的密文，"
                            + "请输入真实值后保存，请在项目详情「环境配置」的「环境参数」中重新录入该键真实值", BizErrorCode.VALIDATION_ERROR);
                }
            }
        }
        if (!invalidKeys.isEmpty()) {
            throw new ServiceException("参数键名不合法（仅允许字母/下划线开头 + 字母数字下划线，"
                    + "且不超过 " + KEY_MAX_LEN + " 字）：" + String.join("、", invalidKeys)
                    + "，请改用字母或下划线开头的键名（示例：db_password）后重新保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (submitted == null || submitted.isEmpty()) {
            root.remove(PARAMS_SECTION);
            return root.toString();
        }
        boolean encryptsAnything = submitted.values().stream().anyMatch(v -> !ConfigCipher.MASK.equals(v));
        if (encryptsAnything && !configCipher.isEnabled()) {
            throw new ServiceException("系统未配置 CONFIG_AES_KEY，无法加密保存环境参数，"
                    + "请先在部署环境配置该密钥后重试，请联系运维在服务部署环境变量中配置「CONFIG_AES_KEY」", BizErrorCode.VALIDATION_ERROR);
        }
        ObjectNode params = root.putObject(PARAMS_SECTION);
        submitted.forEach((key, value) -> {
            if (ConfigCipher.MASK.equals(value)) {
                params.put(key, existing.path(key).asText());
            } else {
                params.put(key, ENC_PREFIX + configCipher.encrypt(value));
            }
        });
        return root.toString();
    }

    
    private static boolean isEncryptedExisting(JsonNode existingParams, String key) {
        JsonNode prev = existingParams.get(key);
        return prev != null && prev.isTextual() && prev.asText().startsWith(ENC_PREFIX);
    }

    
    public void assertParamsSectionUnchanged(String currentConfigJson, String submittedConfigJson) {
        JsonNode currentParams = readParamsSection(currentConfigJson);
        JsonNode submittedParams = readParamsSection(submittedConfigJson);
        if (!currentParams.equals(submittedParams)) {
            throw new ServiceException("环境参数（params）仅能通过参数编辑接口维护，"
                    + "请使用「编辑参数」入口增改删参数后重试", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private JsonNode readParamsSection(String configJson) {
        JsonNode params = readRoot(configJson).path(PARAMS_SECTION);
        return params.isObject() ? params : objectMapper.createObjectNode();
    }

    
    private ObjectNode readRoot(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(configJson);
        } catch (Exception e) {
            throw new ServiceException("环境配置不是合法的 JSON，无法解析参数段，请在项目详情「环境配置」修正后重新保存（正确写法：最外层为对象且含 params 段）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!root.isObject()) {
            throw new ServiceException("环境配置须为 JSON 对象，无法解析参数段，请在项目详情「环境配置」改成 JSON 对象（形如 {\"params\":{...}}）后重新保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode params = root.path(PARAMS_SECTION);
        if (root.has(PARAMS_SECTION) && !params.isObject()) {
            throw new ServiceException("环境配置的 params 段须为 JSON 对象，请在项目详情「环境配置」把 params 改成对象（示例：{\"db_password\":\"...\"}）后重新保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return (ObjectNode) root;
    }
}

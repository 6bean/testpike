package com.sixbean.testpike.core.project.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.TlsContextResolver;
import com.sixbean.testpike.common.util.TlsMode;
import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.springframework.stereotype.Component;


@Component
public class EnvTlsSupport {

    
    public static final String TLS_SECTION = "tls";

    
    public static final String ENC_PREFIX = "ENC:";

    static final String FIELD_MODE = "mode";
    static final String FIELD_REASON = "reason";
    static final String FIELD_CA = "ca_pem";
    static final String FIELD_CLIENT_CERT = "client_cert_pem";
    static final String FIELD_CLIENT_KEY = "client_key_pem";

    private final ConfigCipher configCipher;
    private final ObjectMapper objectMapper;

    public EnvTlsSupport(ConfigCipher configCipher, ObjectMapper objectMapper) {
        this.configCipher = configCipher;
        this.objectMapper = objectMapper;
    }


    
    public String readMode(String configJson) {
        return TlsMode.fromRaw(readTlsSection(configJson).path(FIELD_MODE).asText(null)).raw();
    }

    
    public EnvTlsView readView(String configJson) {
        JsonNode tls = readTlsSection(configJson);
        return new EnvTlsView(readMode(configJson),
                blankToNull(tls.path(FIELD_REASON).asText(null)),
                maskedIfEncrypted(tls.path(FIELD_CA).asText(null)),
                maskedIfEncrypted(tls.path(FIELD_CLIENT_CERT).asText(null)),
                maskedIfEncrypted(tls.path(FIELD_CLIENT_KEY).asText(null)));
    }

    
    public TlsPolicy resolvePolicyFailSafe(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return TlsContextResolver.defaultStrict();
        }
        JsonNode tls;
        try {
            JsonNode root = objectMapper.readTree(configJson);
            tls = root.isObject() ? root.path(TLS_SECTION) : null;
        } catch (Exception e) {
            return TlsContextResolver.defaultStrict();
        }
        if (tls == null || !tls.isObject()) {
            return TlsContextResolver.defaultStrict();
        }
        return TlsContextResolver.resolve(tls.path(FIELD_MODE).asText(null),
                decryptStored(tls.path(FIELD_CA).asText(null)),
                decryptStored(tls.path(FIELD_CLIENT_CERT).asText(null)),
                decryptStored(tls.path(FIELD_CLIENT_KEY).asText(null)));
    }

    
    private String decryptStored(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return stored.startsWith(ENC_PREFIX)
                ? configCipher.decrypt(stored.substring(ENC_PREFIX.length()))
                : stored;
    }


    
    public void assertModeAllowed(int envType, String rawMode) {
        if (envType == 4 && TlsMode.fromRaw(rawMode) == TlsMode.INSECURE) {
            throw new ServiceException("生产环境禁止使用「跳过证书校验」（insecure）：该档会放行伪造证书，"
                    + "生产段只允许 strict 或上传内部 CA 的 custom_ca。请为该环境配置可信证书校验",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public void assertSectionAllowed(int envType, String configJson) {
        String rawMode = readTlsSection(configJson).path(FIELD_MODE).asText(null);
        assertRawModeKnown(rawMode);
        assertModeAllowed(envType, rawMode);
    }

    
    private static void assertRawModeKnown(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return;
        }
        String normalized = rawMode.trim().toLowerCase();
        if (!normalized.equals(TlsMode.RAW_STRICT) && !normalized.equals(TlsMode.RAW_CUSTOM_CA)
                && !normalized.equals(TlsMode.RAW_INSECURE)) {
            throw new ServiceException("TLS 校验档位仅支持 strict（标准校验）/ custom_ca（信任内部 CA）/ "
                    + "insecure（跳过校验，非生产限定），当前值「" + rawMode + "」无效，"
                    + "请在「环境配置」把校验档位改为上述三种之一（正确写法：strict）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }


    
    public String mergeTls(String configJson, String mode, String caPem, String clientCertPem,
                           String clientKeyPem, String reason) {
        assertRawModeKnown(mode);
        ObjectNode root = readRoot(configJson);
        JsonNode existing = root.path(TLS_SECTION);
        ObjectNode tls = existing.isObject() ? (ObjectNode) existing : objectMapper.createObjectNode();
        if (mode != null && !mode.isBlank()) {
            tls.put(FIELD_MODE, mode.trim().toLowerCase());
        } else if (!tls.has(FIELD_MODE)) {
            tls.put(FIELD_MODE, TlsMode.RAW_STRICT);
        }
        if (reason != null && !reason.isBlank()) {
            tls.put(FIELD_REASON, reason.trim());
        }
        mergePemField(tls, FIELD_CA, caPem, existing);
        mergePemField(tls, FIELD_CLIENT_CERT, clientCertPem, existing);
        mergePemField(tls, FIELD_CLIENT_KEY, clientKeyPem, existing);
        root.set(TLS_SECTION, tls);
        return root.toString();
    }

    
    private void mergePemField(ObjectNode tls, String field, String submitted, JsonNode existingSection) {
        if (submitted != null && submitted.startsWith(ENC_PREFIX)) {
            throw new ServiceException("TLS 证书字段 " + field + " 的值不能以 " + ENC_PREFIX
                    + " 开头（密文形态由系统生成，禁止伪装提交），"
                    + "请在「环境配置」重新粘贴证书原文后保存（示例：-----BEGIN CERTIFICATE----- 开头）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String storedValue = existingSection.isObject() ? existingSection.path(field).asText("") : "";
        if (ConfigCipher.MASK.equals(submitted)) {
            if (!storedValue.startsWith(ENC_PREFIX)) {
                throw new ServiceException("TLS 证书字段 " + field + " 提交了打码占位值但原无可保留的密文，"
                        + "请在「环境配置」上传真实证书内容后保存（示例：粘贴 PEM 证书正文）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            tls.put(field, storedValue);
            return;
        }
        if (submitted == null || submitted.isBlank()) {
            tls.remove(field);
            return;
        }
        if (!configCipher.isEnabled()) {
            throw new ServiceException("系统未配置 CONFIG_AES_KEY，无法加密保存 TLS 证书材料，"
                    + "请先在部署环境配置该密钥后重试（格式：CONFIG_AES_KEY=<32字节密钥>）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        tls.put(field, ENC_PREFIX + configCipher.encrypt(submitted));
    }


    
    public void assertTlsSectionUnchanged(String currentConfigJson, String submittedConfigJson) {
        if (!normalizedTlsForGuard(currentConfigJson).equals(normalizedTlsForGuard(submittedConfigJson))) {
            throw new ServiceException("环境 TLS 证书配置（tls 段）仅能通过「TLS/证书」编辑入口维护，"
                    + "请勿在环境表单裸配置中改动 tls 段后重试", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private JsonNode normalizedTlsForGuard(String configJson) {
        JsonNode tls = readTlsSection(configJson);
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put(FIELD_MODE, TlsMode.fromRaw(tls.path(FIELD_MODE).asText(null)).raw());
        copyIfPresent(tls, normalized, FIELD_CA);
        copyIfPresent(tls, normalized, FIELD_CLIENT_CERT);
        copyIfPresent(tls, normalized, FIELD_CLIENT_KEY);
        return normalized;
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        String value = from.path(field).asText("");
        if (!value.isBlank()) {
            to.put(field, value);
        }
    }


    
    private JsonNode readTlsSection(String configJson) {
        JsonNode tls = readRoot(configJson).path(TLS_SECTION);
        return tls.isObject() ? tls : objectMapper.createObjectNode();
    }

    private String maskedIfEncrypted(String stored) {
        return stored != null && stored.startsWith(ENC_PREFIX) ? ConfigCipher.MASK : null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    
    private ObjectNode readRoot(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(configJson);
        } catch (Exception e) {
            throw new ServiceException("环境配置不是合法的 JSON，无法解析 TLS 段，"
                    + "请在「环境配置」检查 TLS 段写法后重新保存（示例：{\"tls\":{...}}）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!root.isObject()) {
            throw new ServiceException("环境配置须为 JSON 对象，无法解析 TLS 段，"
                    + "请在「环境配置」改为对象形态后重新保存（示例：{\"tls\":{...}}）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return (ObjectNode) root;
    }
}

package com.sixbean.testpike.core.batch.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.datafactory.domain.component.DataPoolVarsSupport;
import com.sixbean.testpike.core.executor.domain.component.EnvVarsDecryptSupport;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single point for batch parameter parsing (snapshot pinned version: claim real-time reverse lookup and startup snapshot assembly share the same parsing semantics, and policy prohibits two implementations).
 * <p>Four channels = {@code envVars} (decryption of the environment config_json params section) / {@code connections} (enabled connection decryption).
 * The {@code dataVars} (decryption of available data pool items) and {@code tls} (three-tier decision) channels correspond one-to-one with the protocol claim envelope fields.
 * Visual baseline belongs to real-time observation semantics and is not included in the snapshot (it updates with execution, not a batch parameter).
 * <p>Envelope encoding and decoding = entire JSON package -> {@link ConfigCipher} AES-GCM -> {@code ENC:} prefix written to disk.
 * Policy: snapshots must not contain plaintext input parameters; reuse the same prefix literal single source from {@link EnvParamCipherSupport#ENC_PREFIX}.
 * <p>⚠️ Each channel's return value contains plaintext credentials -- only for claim dispatch or snapshot-envelope in-memory transfer; logging and auditing are forbidden (security pin).
 */
@Component
public class BatchParamResolver {

    /** Snapshot envelope four-channel section names (single literal source shared by assembly, parsing, and claim override read). */
    public static final String SECTION_ENV_VARS = "envVars";
    public static final String SECTION_CONNECTIONS = "connections";
    public static final String SECTION_DATA_VARS = "dataVars";
    public static final String SECTION_TLS = "tls";
    /** Envelope ciphertext prefix (same literal as the EnvParamCipherSupport read side). */
    public static final String ENC_PREFIX = "ENC:";

    private final JdbcTemplate jdbcTemplate;
    private final EnvVarsDecryptSupport envVarsDecryptSupport;
    private final EnvTlsSupport envTlsSupport;
    private final DataPoolVarsSupport dataPoolVarsSupport;
    private final ConfigCipher configCipher;
    private final ObjectMapper objectMapper;

    public BatchParamResolver(JdbcTemplate jdbcTemplate,
                              EnvVarsDecryptSupport envVarsDecryptSupport,
                              EnvTlsSupport envTlsSupport,
                              DataPoolVarsSupport dataPoolVarsSupport,
                              ConfigCipher configCipher, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.envVarsDecryptSupport = envVarsDecryptSupport;
        this.envTlsSupport = envTlsSupport;
        this.dataPoolVarsSupport = dataPoolVarsSupport;
        this.configCipher = configCipher;
        this.objectMapper = objectMapper;
    }

    /** Single reverse-lookup point for environment config_json (shared by envVars and tls channels; empty base_url or deleted environment returns null). */
    public String readEnvConfigJson(long projectId, String envBaseUrl) {
        if (envBaseUrl == null || envBaseUrl.isBlank()) {
            return null;
        }
        return jdbcTemplate.query(
                "SELECT config_json::text FROM tp_project_env WHERE project_id = ? AND base_url = ?"
                        + " ORDER BY id LIMIT 1",
                rs -> {
                    // Cross-task pitfall ledger pin: nullable columns must not combine RowMapper with stream.findFirst.
                    // (null elements passed to Optional.of throw NPE); ResultSetExtractor manually interprets the result internally.
                    return rs.next() ? rs.getString(1) : null;
                }, projectId, envBaseUrl);
    }

    /** envVars channel: decrypted plaintext map of the params section (dirty data fails safe to an empty map and does not block claim acquisition). */
    public Map<String, String> readEnvVars(long projectId, String envBaseUrl) {
        return envVarsDecryptSupport.decryptParams(readEnvConfigJson(projectId, envBaseUrl));
    }

    /** tls channel: three-tier resolution (missing profile or dirty data falls back to strict mode without crashing claim acquisition). */
    public TlsPolicy readTlsPolicy(long projectId, String envBaseUrl) {
        return envTlsSupport.resolvePolicyFailSafe(readEnvConfigJson(projectId, envBaseUrl));
    }

    /** connections channel: enabled connections with status=1 -> list of key-name maps consumed by the executor (including plaintext passwords). */
    public List<Map<String, Object>> readConnections(long projectId, String envBaseUrl) {
        if (envBaseUrl == null || envBaseUrl.isBlank()) {
            return List.of();
        }
        Long envId = jdbcTemplate.query(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND base_url = ?"
                        + " ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, projectId, envBaseUrl);
        if (envId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT name, dialect, jdbc_url, username, password_enc, readonly, max_pool
                FROM tp_db_connection WHERE env_id = ? AND status = 1 ORDER BY id
                """,
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("name", rs.getString("name"));
                    item.put("dialect", rs.getString("dialect"));
                    item.put("url", rs.getString("jdbc_url"));
                    item.put("user", rs.getString("username"));
                    item.put("password", configCipher.decrypt(rs.getString("password_enc")));
                    item.put("readonly", rs.getBoolean("readonly"));
                    item.put("maxPool", rs.getInt("max_pool"));
                    return item;
                }, envId);
    }

    /** dataVars channel: name-to-decrypted-plaintext map of available pool items (borrowed-state semantics are not part of the snapshot; the pinned content is the values). */
    public Map<String, String> readDataVars(long projectId, String envBaseUrl) {
        return dataPoolVarsSupport.decryptPoolVars(projectId, envBaseUrl);
    }

    /** Full assembly of the four channels (the startup snapshot and claim override read share the same shape; the tls section is flattened into mode/ca/cert/key text). */
    public Map<String, Object> assembleSections(long projectId, String envBaseUrl) {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put(SECTION_ENV_VARS, readEnvVars(projectId, envBaseUrl));
        sections.put(SECTION_CONNECTIONS, readConnections(projectId, envBaseUrl));
        sections.put(SECTION_DATA_VARS, readDataVars(projectId, envBaseUrl));
        TlsPolicy tls = readTlsPolicy(projectId, envBaseUrl);
        Map<String, Object> tlsSection = new LinkedHashMap<>();
        tlsSection.put("mode", tls.mode().raw());
        tlsSection.put("caPem", tls.caPem());
        tlsSection.put("clientCertPem", tls.clientCertPem());
        tlsSection.put("clientKeyPem", tls.clientKeyPem());
        sections.put(SECTION_TLS, tlsSection);
        return sections;
    }

    /** Plaintext sections -> ENC envelope (serialize the entire JSON package, then AES-GCM; if the key is not configured, reject with a human-readable error and forbid plaintext fallback). */
    public String toEnvelope(Map<String, Object> sections) {
        if (!configCipher.isEnabled()) {
            throw new ServiceException("系统未配置 CONFIG_AES_KEY，无法加密保存批次参数快照"
                            + "——请装机时在部署配置（.env）写入该密钥后重启服务"
                            + "（格式：CONFIG_AES_KEY=<32 字节 Base64 值>）",
                    BizErrorCode.INTERNAL_ERROR);
        }
        try {
            return ENC_PREFIX + configCipher.encrypt(objectMapper.writeValueAsString(sections));
        } catch (Exception e) {
            throw new ServiceException("批次参数快照加密失败——请重新发起本次批次由系统重建参数快照"
                    + "（示例：提交时重新选择一遍批次参数即可）；持续失败请核对部署配置（.env）的 CONFIG_AES_KEY",
                BizErrorCode.INTERNAL_ERROR);
        }
    }

    /** ENC envelope -> plaintext section map (decryption failure is treated as dirty data and throws a human-readable error; the caller falls back to real-time reverse lookup). */
    public Map<String, Object> fromEnvelope(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) {
            throw new ServiceException("批次参数快照密文形态异常——本行快照不是标准密文，系统将回退为实时反查；"
                    + "如需修复该行，请重新提交一次批次生成新快照（示例：新批次记录以标准密文落库）",
                    BizErrorCode.INTERNAL_ERROR);
        }
        try {
            String plain = configCipher.decrypt(stored.substring(ENC_PREFIX.length()));
            return objectMapper.readValue(plain,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class,
                            String.class, Object.class));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("批次参数快照解密失败——多为密钥已轮换而旧快照未重写，请重新提交一次批次重建参数快照；"
                    + "确需读旧值请核对部署配置（.env）中 CONFIG_AES_KEY 与落库时刻是否同源"
                    + "（示例：保留旧密钥解密归档后再换新钥）", BizErrorCode.INTERNAL_ERROR);
        }
    }
}

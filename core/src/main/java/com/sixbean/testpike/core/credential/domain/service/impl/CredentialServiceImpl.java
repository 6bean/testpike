package com.sixbean.testpike.core.credential.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.credential.api.dto.CredentialInfo;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.credential.infrastructure.dataobject.Credential;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.project.domain.component.EnvParamCipherSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Service
public class CredentialServiceImpl implements CredentialService {

    private static final String SELECT_COLUMNS = """
            id, project_id, name, cred_key, secret_cipher, status, remark,
            test_evidence, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigCipher configCipher;

    public CredentialServiceImpl(NamedParameterJdbcTemplate jdbc, ConfigCipher configCipher) {
        this.jdbc = jdbc;
        this.configCipher = configCipher;
    }

    @Override
    @Audit(objectType = "credential", action = "create", objectId = "#result")
    public long create(long projectId, CredentialInput input) {
        validateName(input);
        List<String> invalidKeys = new ArrayList<>();
        String key = normalizeKey(input.credKey(), invalidKeys);
        if (!invalidKeys.isEmpty()) {
            throw invalidKeyError(invalidKeys);
        }
        requireSecret(input.secret(), key);
        String cipher = encryptOrThrow(input.secret());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("name", input.name().trim())
                .addValue("key", key)
                .addValue("cipher", cipher)
                .addValue("remark", trimToNull(input.remark()));
        try {
            Long inserted = jdbc.queryForObject("INSERT INTO tp_credential "
                            + "(project_id, name, cred_key, secret_cipher, remark) "
                            + "VALUES (:pid, :name, :key, :cipher, :remark) RETURNING id",
                    params, Long.class);
            return inserted == null ? 0L : inserted;
        } catch (DuplicateKeyException e) {
            throw conflict(key);
        }
    }

    @Override
    @Audit(objectType = "credential", action = "update", objectId = "#id")
    public void update(long id, CredentialInput input) {
        Credential current = findOr404(id);
        validateName(input);
        List<String> invalidKeys = new ArrayList<>();
        String key = normalizeKey(input.credKey(), invalidKeys);
        if (!invalidKeys.isEmpty()) {
            throw invalidKeyError(invalidKeys);
        }
        boolean keepExisting = ConfigCipher.MASK.equals(input.secret());
        String cipher;
        if (keepExisting) {
            cipher = current.getSecretCipher();
        } else {
            requireSecret(input.secret(), key);
            cipher = encryptOrThrow(input.secret());
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", input.name().trim())
                .addValue("key", key)
                .addValue("cipher", cipher)
                .addValue("remark", trimToNull(input.remark()));
        try {
            jdbc.update("UPDATE tp_credential SET name = :name, cred_key = :key, "
                    + "secret_cipher = :cipher, remark = :remark, updated_at = now() "
                    + "WHERE id = :id", params);
        } catch (DuplicateKeyException e) {
            throw conflict(key);
        }
    }

    @Override
    public CredentialInfo get(long id) {
        return toMaskedView(findOr404(id));
    }

    @Override
    public List<CredentialInfo> list(long projectId, Boolean enabledOnly) {
        MapSqlParameterSource params = new MapSqlParameterSource("pid", projectId);
        String where = "project_id = :pid";
        if (Boolean.TRUE.equals(enabledOnly)) {
            where += " AND status = 1";
        }
        return findRows(where, params).stream().map(this::toMaskedView).toList();
    }

    @Override
    @Audit(objectType = "credential", action = "set-enabled", objectId = "#id")
    public void setEnabled(long id, boolean enabled) {
        findOr404(id);
        jdbc.update("UPDATE tp_credential SET status = :st, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("st", enabled ? 1 : 0));
    }

    @Override
    @Audit(objectType = "credential", action = "delete", objectId = "#id")
    public void delete(long id) {
        findOr404(id);
        jdbc.update("DELETE FROM tp_credential WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Override
    @Audit(objectType = "credential", action = "reveal", objectId = "#id")
    public String revealSecret(long id) {
        return configCipher.decrypt(stripPrefix(findOr404(id).getSecretCipher()));
    }

    @Override
    @Audit(objectType = "credential", action = "resolve", objectId = "#credKey")
    public String resolveSecret(long projectId, String credKey) {
        List<Credential> rows = findRows("project_id = :pid AND cred_key = :key",
                new MapSqlParameterSource("pid", projectId).addValue("key", credKey));
        if (rows.isEmpty()) {
            throw new ServiceException("步骤引用的凭据「" + credKey + "」在本项目未建档——"
                    + "请先到「APP 自动化 → 凭据保险箱」建档，或修正用例步骤里的引用键",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Credential c = rows.get(0);
        if (c.getStatus() == null || c.getStatus() != 1) {
            throw new ServiceException("凭据「" + credKey + "」已禁用，任务无法派发——"
                    + "请到「APP 自动化 → 凭据保险箱」找到该凭据，打开其「启用」列开关后重试（禁用不影响已在跑的历史会话）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return configCipher.decrypt(stripPrefix(c.getSecretCipher()));
    }


    private void validateName(CredentialInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("凭据名称不能为空——请在「APP 自动化 → 凭据保险箱」"
                    + "的新建凭据表单里填写名称，示例：测试环境管理员账号",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private String normalizeKey(String raw, List<String> invalidCollector) {
        if (raw == null || raw.length() > EnvParamCipherSupport.KEY_MAX_LEN
                || !EnvParamCipherSupport.KEY_PATTERN.matcher(raw).matches()) {
            invalidCollector.add(String.valueOf(raw));
            return raw;
        }
        return raw;
    }

    private ServiceException invalidKeyError(List<String> invalidKeys) {
        return new ServiceException("凭据引用键不合法（仅允许字母/下划线开头 + 字母数字下划线，"
                + "且不超过 " + EnvParamCipherSupport.KEY_MAX_LEN + " 字）——请改成正确写法，"
                + "示例：prod_admin。不合法键："
                + String.join("、", invalidKeys), BizErrorCode.VALIDATION_ERROR);
    }

    private void requireSecret(String secret, String key) {
        if (secret == null || secret.isBlank()) {
            throw new ServiceException("凭据「" + key + "」的口令不能为空——请在"
                    + "「APP 自动化 → 凭据保险箱」该凭据条目中填写口令后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (secret.startsWith(EnvParamCipherSupport.ENC_PREFIX)) {
            throw new ServiceException("凭据 " + key + " 的口令不能以 "
                    + EnvParamCipherSupport.ENC_PREFIX + " 开头（密文形态由系统生成，禁止伪装提交）——"
                    + "请填写账号的真实口令，可在「APP 自动化 → 凭据保险箱」的编辑弹窗重新录入",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private String encryptOrThrow(String plain) {
        if (!configCipher.isEnabled()) {
            throw new ServiceException("系统未配置「CONFIG_AES_KEY」，无法加密保存凭据，"
                    + "请先在服务端部署环境的 .env 文件配置该密钥并重启服务后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return EnvParamCipherSupport.ENC_PREFIX + configCipher.encrypt(plain);
    }

    private static String stripPrefix(String stored) {
        return stored != null && stored.startsWith(EnvParamCipherSupport.ENC_PREFIX)
                ? stored.substring(EnvParamCipherSupport.ENC_PREFIX.length())
                : stored;
    }

    private ServiceException conflict(String key) {
        return new ServiceException("本项目已存在同键凭据「" + key
                + "」——请换一个引用键名，或前往「APP 自动化 → 凭据保险箱」直接编辑既有凭据",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    private Credential findOr404(long id) {
        List<Credential> rows = findRows("id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("凭据（编号=" + id + "）不存在，可能已被删除——"
                    + "请刷新「APP 自动化 → 凭据保险箱」列表后从最新记录重新操作",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private List<Credential> findRows(String where, MapSqlParameterSource params) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_credential WHERE " + where,
                params, new BeanPropertyRowMapper<>(Credential.class));
    }

    
    private CredentialInfo toMaskedView(Credential c) {
        return new CredentialInfo(c.getId(), c.getProjectId(), c.getName(), c.getCredKey(),
                ConfigCipher.MASK, c.getStatus(), c.getRemark(), c.getTestEvidence(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

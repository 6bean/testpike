package com.sixbean.testpike.core.config.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.component.AiChatKeyRanges;
import com.sixbean.testpike.core.config.domain.component.AiCtxKeyRanges;
import com.sixbean.testpike.core.config.domain.component.AiGroundKeyRanges;
import com.sixbean.testpike.core.config.domain.component.AiMcpSyncKeyRanges;
import com.sixbean.testpike.core.config.domain.component.AiOcrKeyRanges;
import com.sixbean.testpike.core.config.domain.component.AiPipelineKeyRanges;
import com.sixbean.testpike.core.config.domain.component.AiScriptKeyRanges;
import com.sixbean.testpike.core.config.domain.component.DlqAutoReplayKeyRanges;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.mcp.domain.component.McpEndpointPolicy;
import com.sixbean.testpike.core.notification.domain.component.FeishuWebhookUrlRule;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.math.BigDecimal;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;


@Service
public class ConfigServiceImpl implements ConfigService {

    private static final Set<String> VALUE_TYPES = Set.of("string", "int", "bool", "json");
    private static final int KEY_MAX_LEN = 100;
 
    private static final int LLM_TIMEOUT_MIN_SECONDS = 10;
    private static final int LLM_TIMEOUT_MAX_SECONDS = 900;

    private static final String COLUMNS = """
            id, config_key, config_value, group_name, value_type, encrypt_flag, description
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCipher configCipher;
    private final ConfigCacheHolder cache;
    private final McpEndpointPolicy mcpEndpointPolicy;

    public ConfigServiceImpl(JdbcTemplate jdbcTemplate, ConfigCipher configCipher, ConfigCacheHolder cache,
                             McpEndpointPolicy mcpEndpointPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.configCipher = configCipher;
        this.cache = cache;
        this.mcpEndpointPolicy = mcpEndpointPolicy;
    }

    @PostConstruct
    void loadCache() {
        cache.loadAll(jdbcTemplate);
    }

    @Override
    @Transactional
    @Audit(objectType = "sys_config", action = "create", objectId = "#result?.id", after = "#result")
    public ConfigInfo create(Long operatorId, ConfigInput input) {
        String key = normalizeKey(input.configKey());
        validate(key, input);
        Long exists = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (exists != null) {
            throw new ServiceException("配置项 " + key + " 已存在——请在「系统配置」搜索该键名，改换键名新建或直接编辑既有条目", BizErrorCode.RESOURCE_CONFLICT);
        }
        String stored = encodeValue(input.configValue(), input.encryptFlag() == 1);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, key, stored, input.groupName(), input.valueType(),
                input.encryptFlag(), input.description(), operatorId);
        cache.put(key, input.configValue());
        return getById(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "sys_config", action = "update", objectId = "#id", after = "#result")
    public ConfigInfo update(Long operatorId, long id, ConfigInput input) {
        ConfigInfo current = getById(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        String key = normalizeKey(input.configKey());
        validate(key, input);
        Long dup = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ? AND id <> ?",
                rs -> rs.next() ? rs.getLong(1) : null, key, id);
        if (dup != null) {
            throw new ServiceException("配置项 " + key + " 已存在——请在「系统配置」搜索该键名，改换键名新建或直接编辑既有条目", BizErrorCode.RESOURCE_CONFLICT);
        }
        String stored = encodeValue(input.configValue(), input.encryptFlag() == 1);
        jdbcTemplate.update("""
                UPDATE tp_sys_config SET config_key = ?, config_value = ?, group_name = ?, value_type = ?,
                encrypt_flag = ?, description = ?, updated_at = now() WHERE id = ?
                """, key, stored, input.groupName(), input.valueType(),
                input.encryptFlag(), input.description(), id);
        cache.put(key, input.configValue());
        if (!key.equals(current.configKey())) {
            cache.remove(current.configKey());
        }
        return getById(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "sys_config", action = "delete", objectId = "#id", after = "")
    public void delete(Long operatorId, long id) {
        ConfigInfo current = getById(id);
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE id = ?", id);
        cache.remove(current.configKey());
    }

    @Override
    public List<ConfigInfo> list(String groupName) {
        String sql = "SELECT " + COLUMNS + " FROM tp_sys_config";
        List<Object> args = new ArrayList<>();
        if (groupName != null && !groupName.isBlank()) {
            sql += " WHERE group_name = ?";
            args.add(groupName.trim());
        }
        sql += " ORDER BY group_name, id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> toInfo(rs.getLong("id"),
                rs.getString("config_key"), rs.getString("config_value"),
                rs.getString("group_name"), rs.getString("value_type"),
                rs.getInt("encrypt_flag"), rs.getString("description")), args.toArray());
    }

    @Override
    public String getValue(String configKey, String defaultValue) {
        String cached = cache.get(configKey);
        if (cached != null) {
            return cached;
        }
        String value = jdbcTemplate.query(
                "SELECT config_value, encrypt_flag FROM tp_sys_config WHERE config_key = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    String stored = rs.getString(1);
                    if (stored == null || stored.isBlank()) {
                        return null;
                    }
                    if (rs.getInt(2) == 1) {
                        try {
                            return configCipher.decrypt(stored);
                        } catch (ServiceException e) {
                            return null;
                        }
                    }
                    return stored;
                }, configKey);
        return value != null ? value : defaultValue;
    }

    private ConfigInfo getById(long id) {
        ConfigInfo info = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_sys_config WHERE id = ?",
                rs -> rs.next() ? toInfo(rs.getLong("id"), rs.getString("config_key"),
                        rs.getString("config_value"), rs.getString("group_name"),
                        rs.getString("value_type"), rs.getInt("encrypt_flag"),
                        rs.getString("description")) : null, id);
        if (info == null) {
            throw new ServiceException("配置项不存在——请在「系统配置」刷新列表确认该条目仍在（可能已被删除或换了条目 id）", BizErrorCode.NOT_FOUND);
        }
        return info;
    }

 
    private ConfigInfo toInfo(Long id, String key, String stored, String group,
                              String valueType, int encryptFlag, String description) {
        String shown = encryptFlag == 1 ? ConfigCipher.MASK : stored;
        return new ConfigInfo(id, key, shown, group, valueType, encryptFlag, description);
    }

    
    private String encodeValue(String plain, boolean encrypt) {
        if (!encrypt) {
            return plain == null ? "" : plain;
        }
        if (!configCipher.isEnabled()) {
            throw new ServiceException("系统未配置 CONFIG_AES_KEY，无法加密敏感配置——请先在部署环境变量「CONFIG_AES_KEY」配置加密密钥并重启服务后再保存", BizErrorCode.INTERNAL_ERROR);
        }
        return configCipher.encrypt(plain == null ? "" : plain);
    }

    private void validate(String key, ConfigInput input) {
        if (key == null || key.isBlank()) {
            throw new ServiceException("配置 key 不能为空——请在「系统配置」新建表单填写「配置项」（格式：ai.功能.参数名，示例：ai.doc.summary-ratio）", BizErrorCode.VALIDATION_ERROR);
        }
        if (key.length() > KEY_MAX_LEN) {
            throw new ServiceException("配置 key 长度不能超过 " + KEY_MAX_LEN + " 个字符——请在「系统配置」把「配置项」缩短到该长度以内再提交", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.groupName() == null || input.groupName().isBlank()) {
            throw new ServiceException("配置分组不能为空——请在「系统配置」新建表单选择「分组」后再保存", BizErrorCode.VALIDATION_ERROR);
        }
        String type = input.valueType() == null ? "string" : input.valueType();
        if (!VALUE_TYPES.contains(type)) {
            throw new ServiceException("配置值类型仅支持 string/int/bool/json——请在「系统配置」表单把「值类型」改成四者之一后再保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (key.startsWith("retention.") && "int".equals(type)) {
            int days;
            try {
                days = Integer.parseInt(input.configValue().trim());
            } catch (RuntimeException e) {
                throw new ServiceException("保留天数必须是整数（当前值无效）：" + key + "——请在「系统配置」把该项改成整数天数（示例：30）", BizErrorCode.VALIDATION_ERROR);
            }
            if (days <= 0) {
                throw new ServiceException("保留天数必须为正整数（禁止 0/负数）：" + key + "——请在「系统配置」把该项改成 1 以上的整数天数（示例：30）", BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("llm.timeout-seconds".equals(key)) {
            int seconds;
            try {
                seconds = Integer.parseInt(input.configValue().trim());
            } catch (RuntimeException e) {
                throw new ServiceException("LLM 超时秒数必须是整数（当前值无效）：" + input.configValue() + "——请在「系统配置」把 llm.timeout-seconds 改成整数秒（示例：300）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (seconds < LLM_TIMEOUT_MIN_SECONDS || seconds > LLM_TIMEOUT_MAX_SECONDS) {
                throw new ServiceException("LLM 超时秒数必须在 10~900 区间（当前 " + seconds + "）——请在「系统配置」把 llm.timeout-seconds 改成该区间内的整数",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        // Streaming inter-line idle bound: range 5~300s pinned on the save side
        // (LlmTimeoutResolver keeps the same range as a second guard); invalid or
        // out-of-range values are rejected with a 400 that names the allowed range.
        if ("ai.llm.stream-idle-timeout-seconds".equals(key)) {
            int seconds;
            try {
                seconds = Integer.parseInt(input.configValue().trim());
            } catch (RuntimeException e) {
                throw new ServiceException("流式线间空闲秒数必须是整数（当前值无效）：" + input.configValue() + "——请在「系统配置」把 ai.llm.stream-idle-timeout-seconds 改成整数秒（示例：60）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (seconds < 5 || seconds > 300) {
                throw new ServiceException("流式线间空闲秒数必须在 5~300 区间（当前 " + seconds + "）——请在「系统配置」把 ai.llm.stream-idle-timeout-seconds 改成 5~300 内的整数",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if (key.startsWith("ai.doc.summary-") || key.startsWith("ai.doc.reduce-")
                || "ai.doc.budget-safety-factor".equals(key)
                || "ai.doc.extract-reconcile-rounds".equals(key)
                || "ai.doc.extract-reconcile-resume-seconds".equals(key)
                || "ai.doc.review-flag-threshold".equals(key)
                || "ai.doc.group-overlap-ratio".equals(key)
                || "ai.doc.final-bucket-max-tokens".equals(key)
                || "ai.doc.group-max-tokens".equals(key)
                || "ai.doc.group-max-chunks".equals(key)
                || "ai.doc.chunk-context-chars".equals(key)
                || "ai.doc.group-max-chapters".equals(key)
                || "ai.doc.bucket-output-max-tokens".equals(key)
                || "ai.doc.output-amplification-ratio".equals(key)
                || key.endsWith("recall-density-floor-per-ktoken")
                || "ai.doc.llm-timeout-seconds".equals(key)
                || "ai.judge.qa-gen.llm-timeout-seconds".equals(key)
                || "ai.doc.extract-stream.enabled".equals(key)
                || "ai.doc.fidelity-retention-floor".equals(key)
                || "ai.doc.recall-floor".equals(key)
                || "ai.doc.chapter-aligned-grouping.enabled".equals(key)
                || "ai.gen.original-block-max-tokens".equals(key)
                || "ai.gen.resource-pool.segment-cap".equals(key)) {
            validateE8CompressionKey(key, input);
        }
        if (AiScriptKeyRanges.isScriptKey(key)) {
            validateAiScriptKey(key, input);
        }
        // ai.chat.* dialog key group (V296): interval + bool face, single source=AiChatKeyRanges
        if (AiChatKeyRanges.isChatKey(key)) {
            validateAiChatKey(key, input);
        }
        // ai.mcp.sync.* knowledge-sync key group (V344): interval + bool face, single source=AiMcpSyncKeyRanges
        if (AiMcpSyncKeyRanges.isMcpSyncKey(key)) {
            validateAiMcpSyncKey(key, input);
        }
        // ai.ctx.* long-context key group (V350): interval + bool face, single source=AiCtxKeyRanges
        if (AiCtxKeyRanges.isCtxKey(key)) {
            validateAiCtxKey(key, input);
        }
        // ai.ground.* grounding material-role key group (V400): interval faces,
        // single source=AiGroundKeyRanges
        if (AiGroundKeyRanges.isGroundKey(key)) {
            validateAiGroundKey(key, input);
        }
        // mq.dlq.* dead-letter auto-replay key group (V403): int faces incl. the 0/1
        // master switch, single source=DlqAutoReplayKeyRanges
        if (DlqAutoReplayKeyRanges.isDlqKey(key)) {
            validateDlqAutoReplayKey(key, input);
        }
        // ai.ocr.* vision-less OCR fallback key group (V520, BF batch B1b):
        // interval + bool face, single source=AiOcrKeyRanges
        if (AiOcrKeyRanges.isOcrKey(key)) {
            validateAiOcrKey(key, input);
        }
        // ai.pipeline.* extraction-pipeline tuning key group (V529/V530, AY
        // batch / : int intervals only, single
        // source=AiPipelineKeyRanges — caps are adjudicated, so an above-cap
        // value is refused at save time instead of silently clamped
        if (AiPipelineKeyRanges.isPipelineKey(key)) {
            validateAiPipelineKey(key, input);
        }
        if ("ai.batch.max-count".equals(key) && "int".equals(type)) {
            requireIntInRange(key, input.configValue(), 1, 500);
        }
        if ("apitest.import.duration-ms".equals(key) && "int".equals(type)) {
            requireIntInRange(key, input.configValue(), 100, 600000);
        }
        if ("apitest.spec-extract.max-chunks".equals(key) && "int".equals(type)) {
            requireIntInRange(key, input.configValue(), 1, 200);
        }
        if ("apitest.import.body-check-path".equals(key) && "string".equals(type)) {
            String path = input.configValue() == null ? "" : input.configValue().trim();
            if (!path.isEmpty() && !path.startsWith("$")) {
                throw new ServiceException("响应约定断言路径必须是 JsonPath（以 $ 开头，"
                        + "如 $.code；留空=不生成该断言行）：当前值 " + path + "——请在「系统配置」把 apitest.import.body-check-path 改成 $ 开头的路径或留空（示例：$.code）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("prompt.gen.traffic-pct".equals(key) && "int".equals(type)) {
            requireIntInRange(key, input.configValue(), 0, 100);
        }
        if (("exec.parallel.max".equals(key) || "exec.parallel.per-project".equals(key))
                && "int".equals(type)) {
            int limit;
            try {
                limit = Integer.parseInt(String.valueOf(input.configValue()).trim());
            } catch (RuntimeException e) {
                throw new ServiceException("并行度上限必须是整数（禁止非数字）：" + key + "——请在「系统配置」把该项改成整数（示例：4）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (limit < 0) {
                throw new ServiceException("并行度上限必须为非负整数（0=不限）：" + key + "——请在「系统配置」把该项改成 0 或正整数（0=不限）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("exec.snapshot.max-bytes".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int value;
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new ServiceException("协议快照预算必须是 1024~10485760 之间的整数（当前值无效）："
                        + raw + "——请在「系统配置」把 exec.snapshot.max-bytes 改成同区间整数（示例：1048576）", BizErrorCode.VALIDATION_ERROR);
            }
            if (value < 1024 || value > 10485760) {
                throw new ServiceException("协议快照预算必须在 1024~10485760 区间（当前 " + value + "）——请在「系统配置」把 exec.snapshot.max-bytes 改成该区间内的整数",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("ci.verdict.pass-rate".equals(key)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            BigDecimal rate;
            try {
                rate = new BigDecimal(raw);
            } catch (NumberFormatException e) {
                throw new ServiceException("CI 门禁通过率阈值必须是 0~1 之间的数字（当前值无效）："
                        + raw + "——请在「系统配置」把 ci.verdict.pass-rate 改成 0~1 的数字（示例：0.95）", BizErrorCode.VALIDATION_ERROR);
            }
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw new ServiceException("CI 门禁通过率阈值必须在 0~1 区间（当前 " + raw + "）——请在「系统配置」把 ci.verdict.pass-rate 改成 0~1 内的数字",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("ci.verdict.p0-zero".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int flag;
            try {
                flag = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                flag = -1;
            }
            if (flag != 0 && flag != 1) {
                throw new ServiceException("P0 零失败开关仅支持 0 或 1（当前值无效）：" + raw + "——请在「系统配置」把 ci.verdict.p0-zero 改成 0 或 1",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("defect.auto-create.enabled".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int flag;
            try {
                flag = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                flag = -1;
            }
            if (flag != 0 && flag != 1) {
                throw new ServiceException("自动建单开关仅支持 0 或 1（当前值无效）：" + raw + "——请在「系统配置」把 defect.auto-create.enabled 改成 0 或 1",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("defect.auto-create.dedup-window".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int hours;
            try {
                hours = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new ServiceException("自动建单去重窗口必须是 1~720 之间的整数（当前值无效）："
                        + raw + "——请在「系统配置」把 defect.auto-create.dedup-window 改成同区间整数（示例：24）", BizErrorCode.VALIDATION_ERROR);
            }
            if (hours < 1 || hours > 720) {
                throw new ServiceException("自动建单去重窗口必须在 1~720 小时区间（当前 " + hours + "）——请在「系统配置」把 defect.auto-create.dedup-window 改成 1~720 内的整数",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("jira.sync.enabled".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int flag;
            try {
                flag = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                flag = -1;
            }
            if (flag != 0 && flag != 1) {
                throw new ServiceException("JIRA 同步开关仅支持 0 或 1（当前值无效）：" + raw + "——请在「系统配置」把 jira.sync.enabled 改成 0 或 1",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("jira.sync.timeout-seconds".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int seconds;
            try {
                seconds = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new ServiceException("JIRA 出站超时秒必须是 1~60 之间的整数（当前值无效）："
                        + raw + "——请在「系统配置」把 jira.sync.timeout-seconds 改成同区间整数（示例：10）", BizErrorCode.VALIDATION_ERROR);
            }
            if (seconds < 1 || seconds > 60) {
                throw new ServiceException("JIRA 出站超时秒必须在 1~60 秒区间（当前 " + seconds + "）——请在「系统配置」把 jira.sync.timeout-seconds 改成 1~60 内的整数",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("notify.feishu.enabled".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int flag;
            try {
                flag = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                flag = -1;
            }
            if (flag != 0 && flag != 1) {
                throw new ServiceException("飞书渠道开关仅支持 0 或 1（当前值无效）：" + raw + "——请在「系统配置」把 notify.feishu.enabled 改成 0 或 1",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("ai.repair.enabled".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int flag;
            try {
                flag = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                flag = -1;
            }
            if (flag != 0 && flag != 1) {
                throw new ServiceException("失败自动修复开关仅支持 0 或 1（当前值无效）：" + raw + "——请在「系统配置」把 ai.repair.enabled 改成 0 或 1",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("ai.repair.rounds".equals(key) && "int".equals(type)) {
            String raw = input.configValue() == null ? "" : input.configValue().trim();
            int rounds;
            try {
                rounds = Integer.parseInt(raw);
            } catch (RuntimeException e) {
                throw new ServiceException("修复轮次上限必须是正整数（当前值无效）：" + raw + "——请在「系统配置」把 ai.repair.rounds 改成整数（示例：3）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (rounds < 1) {
                throw new ServiceException("修复轮次上限必须为正整数（当前 " + rounds + "）——请在「系统配置」把 ai.repair.rounds 改成 1 以上的整数；要整体关闭修复请把 ai.repair.enabled 开关改成 0",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("notify.feishu.webhook".equals(key) && "string".equals(type)) {
            validateFeishuWebhook(input.configValue());
        }
    }

    
    private void validateFeishuWebhook(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            return;
        }
        mcpEndpointPolicy.validate(value, "webhook 地址", "飞书 ");
        if (!FeishuWebhookUrlRule.isHttpsOrLoopback(value)) {
            throw new ServiceException("飞书 webhook 地址「" + value + "」"
                    + FeishuWebhookUrlRule.ruleText() + "，拒绝保存——请在「系统配置」把 notify.feishu.webhook 改成符合上述格式的完整地址（示例：https://open.feishu.cn/open-apis/bot/v2/hook/xxx）", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private void requireIntInRange(String key, String rawValue, int min, int max) {
        String raw = rawValue == null ? "" : rawValue.trim();
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ServiceException("配置项 " + key + " 必须是整数（当前值无效）：" + raw + "——请在「系统配置」搜索该配置项，把它改成整数（示例：30）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (value < min || value > max) {
            throw new ServiceException("配置项 " + key + " 必须在 " + min + "~" + max
                    + " 区间（当前 " + value + "）——请在「系统配置」搜索该配置项，把它改成区间内的整数", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private void validateAiScriptKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        int[] range = AiScriptKeyRanges.rangeOf(key);
        if (range == null) {
            requireGroupBoolValue("脚本参数", key, raw);
            return;
        }
        requireGroupIntInRange("脚本参数", key, raw, range);
    }

    /**
     * ai.chat.* write-side guard (AO dialog config group): int keys hit the
     * interval table, bool switches accept true/false only. Out-of-range or
     * non-numeric values are rejected with the allowed range spelled out so
     * the operator can fix the typo from the message alone.
     */
    private void validateAiChatKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        double[] doubleRange = AiChatKeyRanges.doubleRangeOf(key);
        if (doubleRange != null) {
            requireGroupDoubleInRange("对话配置", key, raw, doubleRange);
            return;
        }
        int[] range = AiChatKeyRanges.rangeOf(key);
        if (range == null) {
            if (AiChatKeyRanges.isBoolKey(key)) {
                requireGroupBoolValue("对话配置", key, raw);
            }
            return;
        }
        requireGroupIntInRange("对话配置", key, raw, range);
    }

    /**
     * ai.ground.* write-side guard (grounding material-role group): the score
     * bar is a ratio, the stale window is a day count — both are rejected off
     * the interval with the allowed range spelled out in the message.
     */
    private void validateAiGroundKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        double[] doubleRange = AiGroundKeyRanges.doubleRangeOf(key);
        if (doubleRange != null) {
            requireGroupDoubleInRange("材料配置", key, raw, doubleRange);
            return;
        }
        int[] range = AiGroundKeyRanges.rangeOf(key);
        if (range != null) {
            requireGroupIntInRange("材料配置", key, raw, range);
        }
    }

    /**
     * mq.dlq.* write-side guard (dead-letter operations group): every member is
     * an int face — the master switch follows the existing 0/1 convention of
     * *.enabled keys, the others are seconds/counts. Off-interval writes are
     * rejected with the allowed range spelled out so the operator can fix the
     * typo from the message alone.
     */
    private void validateDlqAutoReplayKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        int[] range = DlqAutoReplayKeyRanges.rangeOf(key);
        if (range != null) {
            requireGroupIntInRange("死信配置", key, raw, range);
        }
    }

    /**
     * ai.ocr.* write-side guard (vision-less fallback group): the density
     * floor is a line count inside 1~20, the gate is a strict true/false —
     * off-interval or non-boolean writes are rejected with the allowed
     * range spelled out so the operator can fix the typo from the message.
     */
    private void validateAiOcrKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        int[] range = AiOcrKeyRanges.rangeOf(key);
        if (range == null) {
            if (AiOcrKeyRanges.isBoolKey(key)) {
                requireGroupBoolValue("OCR 降级配置", key, raw);
            }
            return;
        }
        requireGroupIntInRange("OCR 降级配置", key, raw, range);
    }

    /**
     * Shared write-face for table-driven K8 groups (R40 single source, second
     * consumer = ai.ground): ratio keys must parse and sit inside the group's
     * double interval. The label argument keeps the ai.chat wording
     * byte-identical so neither current assertions nor operator texts drift.
     */
    private void requireGroupDoubleInRange(String label, String key, String raw, double[] range) {
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new ServiceException(label + " " + key + " 必须是数字（当前值无效）：" + raw
                    + "——请在「系统配置」搜索该参数名，把它改成数字（示例：0.5）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (value < range[0] || value > range[1]) {
            throw new ServiceException(label + " " + key + " 必须在 " + range[0] + "~" + range[1]
                    + " 区间（当前 " + value + "）——请在「系统配置」搜索该参数名，把它改成区间内的数字", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
 * Shared write-face for table-driven K8 groups (R40 single source): int keys
 * must parse and sit inside the group's interval table, bool switches accept
 * true/false only. The label argument keeps every group's existing message
 * current assertions nor operator-facing texts drift.
 */
    private void requireGroupIntInRange(String label, String key, String raw, int[] range) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ServiceException(label + " " + key + " 必须是整数（当前值无效）：" + raw
                    + "——请在「系统配置」搜索该参数名，把它改成整数（示例：30）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (value < range[0] || value > range[1]) {
            throw new ServiceException(label + " " + key + " 必须在 " + range[0] + "~" + range[1]
                    + " 区间（当前 " + value + "）——请在「系统配置」搜索该参数名，把它改成区间内的整数", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void requireGroupBoolValue(String label, String key, String raw) {
        if (!"true".equals(raw) && !"false".equals(raw)) {
            throw new ServiceException(label + " " + key + " 仅支持 true/false（当前 " + raw + "）——请在「系统配置」搜索该参数名，把它改成 true 或 false",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * ai.mcp.sync.* write-side guard (knowledge-sync config group): same faces as
     * the other table-driven groups, consumed through the shared helpers above.
     */
    private void validateAiMcpSyncKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        int[] range = AiMcpSyncKeyRanges.rangeOf(key);
        if (range == null) {
            if (AiMcpSyncKeyRanges.isBoolKey(key)) {
                requireGroupBoolValue("MCP 同步配置", key, raw);
            }
            return;
        }
        requireGroupIntInRange("MCP 同步配置", key, raw, range);
    }

    /**
     * ai.ctx.* write-side guard (long-context config group): intervals mirror
     * the reader clamp in AiCtxConfigResolver on purpose. The group carries no
     * bool member since V455 retired ai.ctx.doc-summaries-cache to
     * ai.doc.summary-cache.enabled, so non-interval keys pass through.
     */
    private void validateAiCtxKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        int[] range = AiCtxKeyRanges.rangeOf(key);
        if (range == null) {
            return;
        }
        requireGroupIntInRange("长上下文配置", key, raw, range);
    }

    /**
 * ai.pipeline.* write-side guard (extraction-pipeline tuning group, AY
 * batch : intervals mirror the reader clamps in
 * GroupParallelFanout (cap 3) and DocExtractServiceImpl (retry bounds
 * 0~2) on purpose. The group carries no bool member, so unlisted keys
 * pass through. Above-cap values are rejected here rather than clamped:
 * the caps are ledger-derived adjudications, not config freedom.
 */
    private void validateAiPipelineKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        int[] range = AiPipelineKeyRanges.rangeOf(key);
        if (range == null) {
            return;
        }
        requireGroupIntInRange("分组并行与长尾重提配置", key, raw, range);
    }

 
    private void validateE8CompressionKey(String key, ConfigInput input) {
        String raw = input.configValue() == null ? "" : input.configValue().trim();
        // /: every per-domain recall-density floor (doc, spec,
        // judge, scenario-enumeration, mock-draft) shares this one window —
        // 0 = check off, 50 = hard upper bound, same bounds as the
        // ai-service readers' fail-soft posture. Suffix match so a new
        // calibrated domain key is validated the moment it ships, no
        // second enumeration of key names to keep in sync.
        try {
            if (key.endsWith("recall-density-floor-per-ktoken")) {
                double v = Double.parseDouble(raw);
                if (v < 0 || v > 50) {
                    throw new ServiceException(
                            "每千 token 产出条数下限必须在 0~50 区间（当前 " + v
                                    + "），填 0 表示关闭该校验——请在「系统配置」搜索该参数名，把它改成 0~50 内的数字（0=关闭校验）",
                            BizErrorCode.VALIDATION_ERROR);
                }
                return;
            }
            switch (key) {
                case "ai.doc.summary-cache.enabled" ->
                        requireGroupBoolValue("分组摘要缓存开关", key, raw);
                case "ai.doc.extract-reconcile-rounds" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 1 || v > 5) {
                        throw new ServiceException("定向补提轮数必须在 1~5 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.extract-reconcile-rounds 改成 1~5 内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                // D2 recovery window (defect ledger, same-generation resume):
                // 0 = recovery off (legacy bleed-out face), otherwise the
                // adjudicated 300~3600 band. The upper bound keeps the resume
                // under the delivery-ack headroom invariant (ack = 2x task
                // deadline, D1 ledger) so a resume can never replay the 406
                // near-miss; the lower bound keeps a resume meaningful.
                case "ai.doc.extract-reconcile-resume-seconds" -> {
                    int v = Integer.parseInt(raw);
                    if (v != 0 && (v < 300 || v > 3600)) {
                        throw new ServiceException(
                                "复核轮续窗秒数必须为 0（关闭续期）或 300~3600 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.extract-reconcile-resume-seconds 改成 0 或 300~3600 内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.budget-safety-factor" -> {
                    double v = Double.parseDouble(raw);
                    if (v < 0.1 || v > 0.95) {
                        throw new ServiceException("预算安全系数必须在 0.1~0.95 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.budget-safety-factor 改成 0.1~0.95 内的数字（示例：0.6）",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.review-flag-threshold" -> {
                    double v = Double.parseDouble(raw);
                    if (v < 0.1 || v > 1.0) {
                        throw new ServiceException("待核对置信度阈值必须在 0.1~1 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.review-flag-threshold 改成 0.1~1 内的数字（示例：0.7）",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                // Extraction streaming switch: bool face only, same shared wording
                // as the other key groups (garbage rejected with a 400 naming
                // true/false so the operator knows the allowed values).
                case "ai.doc.extract-stream.enabled" ->
                        requireGroupBoolValue("提取流式开关", key, raw);
                // Chapter-aligned grouping switch : bool face only,
                // same shared wording as the other key groups.
                case "ai.doc.chapter-aligned-grouping.enabled" ->
                        requireGroupBoolValue("章节对齐分组开关", key, raw);
                // Fidelity retention floor : the ratio of source
                // tokens the extraction plane must still inject; a floor
                // outside 0~1 would either judge nothing or judge everything,
                // so the plain 400 names the window (the reader fails closed
                // to the factory 0.95 with the same bound).
                case "ai.doc.fidelity-retention-floor" -> {
                    double v = Double.parseDouble(raw);
                    if (v < 0.0 || v > 1.0) {
                        throw new ServiceException("提取原文保留比例下限必须在 0~1 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.fidelity-retention-floor 改成 0~1 内的数字（示例：0.95）",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                // Recall floor : the golden-set recall regression
                // check judges every live run against this ratio; a bound
                // outside 0~1 would either judge nothing or judge everything,
                // so the plain 400 names the window (the spectrum reader
                // fails closed to the factory 0.85 with the same bound).
                case "ai.doc.recall-floor" -> {
                    double v = Double.parseDouble(raw);
                    if (v < 0.0 || v > 1.0) {
                        throw new ServiceException("需求点召回率下限必须在 0~1 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.recall-floor 改成 0~1 内的数字（示例：0.85）",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                // Extraction-plane timeout ceiling : blank keeps
                // the global llm.timeout-seconds face; anything else must sit in
                // the same 10~900 window the global key pins (bounds read-side by
                // LlmTimeoutResolver.resolveDocExtract, double-guarded here).
                case "ai.doc.llm-timeout-seconds" -> {
                    if (!raw.isBlank()) {
                        int v = Integer.parseInt(raw);
                        if (v < 10 || v > 900) {
                            throw new ServiceException(
                                    "提取链超时上限必须在 10~900 秒区间，或留空沿用全局超时（当前 " + v + "）——请在「系统配置」把 ai.doc.llm-timeout-seconds 改成该区间整数或清空该项",
                                    BizErrorCode.VALIDATION_ERROR);
                        }
                    }
                }
                // Question-generation plane timeout ceiling :
                // blank keeps the global llm.timeout-seconds face; anything
                // else must sit in the same 10~900 window the global key pins
                // (bounds read-side by LlmTimeoutResolver.resolveJudgeQaGen,
                // double-guarded here).
                case "ai.judge.qa-gen.llm-timeout-seconds" -> {
                    if (!raw.isBlank()) {
                        int v = Integer.parseInt(raw);
                        if (v < 10 || v > 900) {
                            throw new ServiceException(
                                    "出题生成链超时上限必须在 10~900 秒区间，或留空沿用全局超时（当前 " + v + "）——请在「系统配置」把 ai.judge.qa-gen.llm-timeout-seconds 改成该区间整数或清空该项",
                                    BizErrorCode.VALIDATION_ERROR);
                        }
                    }
                }
                // Group-overlap ratio (grouping window shared with chapter
                // bucketing): plain 400 naming the operator-allowed window.
                case "ai.doc.group-overlap-ratio" -> {
                    double v = Double.parseDouble(raw);
                    if (v < 0.0 || v > 0.25) {
                        throw new ServiceException("封组重叠比例必须在 0~0.25 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.group-overlap-ratio 改成 0~0.25 内的数字（示例：0.1）",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                // Final-extract bucket ceiling : blank keeps the
                // automatic face, otherwise a non-negative whole token count
                // (0 = bucketing disabled). Plain 400 names the rule; the same
                // window is judged read-side by ContextBudgetPlanner.
                case "ai.doc.final-bucket-max-tokens" -> {
                    if (!raw.isBlank()) {
                        int v = Integer.parseInt(raw);
                        if (v < 0) {
                            throw new ServiceException(
                                    "最终提取分桶上限必须留空（自动）或为 0 及以上的正整数 token 数（当前 " + v + "）——请在「系统配置」把 ai.doc.final-bucket-max-tokens 留空或改成 0 以上的整数",
                                    BizErrorCode.VALIDATION_ERROR);
                        }
                    }
                }
                // node caps: the same windows the ai-service reader
                // fail-closes to (ContextBudgetPlanner groupMaxTokensOf &
                // friends); module direction forbids importing that class
                // here, the double-ended contract keeps both faces aligned.
                case "ai.doc.group-max-tokens" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 256 || v > 100000) {
                        throw new ServiceException(
                                "封组 token 上限必须在 256~100000 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.group-max-tokens 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                // : same window as the ai-service reader face
                // (OriginalTextBlockAssembler.tokensOrNull); module direction
                // forbids importing that class here, the double-ended
                // contract keeps both faces aligned.
                case "ai.gen.original-block-max-tokens" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 256 || v > 100000) {
                        throw new ServiceException(
                                "用例原文注入预算 token 数必须在 256~100000 区间（当前 " + v + "）——请在「系统配置」把 ai.gen.original-block-max-tokens 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                // segment b: same window as the ai-service fail-closed
                // reader face (AiGenServiceImpl.resourceSegmentCap); a save
                // outside the window is refused here, a bad historic value
                // still falls back to the factory default at read time.
                case "ai.gen.resource-pool.segment-cap" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 10 || v > 100) {
                        throw new ServiceException(
                                "资源池清单条目上限必须在 10~100 区间（当前 " + v + "）——请在「系统配置」把 ai.gen.resource-pool.segment-cap 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.group-max-chunks" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 1 || v > 500) {
                        throw new ServiceException(
                                "封组块数上限必须在 1~500 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.group-max-chunks 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.chunk-context-chars" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 1000 || v > 20000) {
                        throw new ServiceException(
                                "回看上下文预算字数必须在 1000~20000 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.chunk-context-chars 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.group-max-chapters" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 1 || v > 1000) {
                        throw new ServiceException(
                                "封组章跨度上限必须在 1~1000 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.group-max-chapters 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.bucket-output-max-tokens" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 1000 || v > 200000) {
                        throw new ServiceException(
                                "单组预估产出 token 上限必须在 1000~200000 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.bucket-output-max-tokens 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.output-amplification-ratio" -> {
                    double v = Double.parseDouble(raw);
                    if (v < 0.1 || v > 10.0) {
                        throw new ServiceException(
                                "产出放大系数必须在 0.1~10 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.output-amplification-ratio 改成该区间内的数字",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.summary-ratio" -> {
                    double v = Double.parseDouble(raw);
                    if (v < 0.05 || v > 0.9) {
                        throw new ServiceException("摘要比例必须在 0.05~0.9 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.summary-ratio 改成 0.05~0.9 内的数字",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.summary-min-tokens" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 50 || v > 20000) {
                        throw new ServiceException("摘要 token 下限必须在 50~20000 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.summary-min-tokens 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                    assertNotCross(v, "ai.doc.summary-max-tokens", true);
                }
                case "ai.doc.summary-max-tokens" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 50 || v > 20000) {
                        throw new ServiceException("摘要 token 上限必须在 50~20000 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.summary-max-tokens 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                    assertNotCross(v, "ai.doc.summary-min-tokens", false);
                }
                case "ai.doc.reduce-trigger-groups" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 2 || v > 64) {
                        throw new ServiceException("归并触发组数必须在 2~64 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.reduce-trigger-groups 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                case "ai.doc.reduce-max-levels" -> {
                    int v = Integer.parseInt(raw);
                    if (v < 1 || v > 3) {
                        throw new ServiceException("归并层级上限必须在 1~3 区间（当前 " + v + "）——请在「系统配置」把 ai.doc.reduce-max-levels 改成该区间内的整数",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                }
                default -> { }
            }
        } catch (NumberFormatException e) {
            throw new ServiceException("压缩策略配置值必须是数字（当前值无效）：" + key + "——请在「系统配置」搜索该参数名，把它改成数字（示例：0.5）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

 
    private void assertNotCross(int value, String siblingKey, boolean selfIsMin) {
        String sibling = jdbcTemplate.query(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                (org.springframework.jdbc.core.ResultSetExtractor<String>) rs ->
                        rs.next() ? rs.getString(1) : null, siblingKey);
        if (sibling == null || sibling.isBlank()) {
            return;
        }
        try {
            int other = Integer.parseInt(sibling.trim());
            if (selfIsMin ? value > other : value < other) {
                throw new ServiceException("摘要 token 上下限交叉非法：下限不得大于上限（当前对侧 "
                        + siblingKey + "=" + other + "）——请在「系统配置」把 ai.doc.summary-min-tokens 与 ai.doc.summary-max-tokens 改成下限不大于上限", BizErrorCode.VALIDATION_ERROR);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private String normalizeKey(String key) {
        return key == null ? null : key.trim();
    }
}

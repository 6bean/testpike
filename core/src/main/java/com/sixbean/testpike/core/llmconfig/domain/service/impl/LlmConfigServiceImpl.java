package com.sixbean.testpike.core.llmconfig.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigBrief;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.api.dto.ProjectLlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.domain.component.GoldenRebindReplayTrigger;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;


@Service
public class LlmConfigServiceImpl implements LlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigServiceImpl.class);

    private static final String COLUMNS = """
            id, name, provider, model_name, api_key, base_url, status, is_default, description,
            prompt_limit_tokens, tokenizer_family, model_type, embedding_dimension
            """;

 
    private static final String BRIEF_COLUMNS = """
            id, name, provider, model_name, api_key, base_url, prompt_limit_tokens, tokenizer_family,
            model_type, embedding_dimension, cache_hint, structured_output
            """;

    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbc;
    private final ConfigCipher configCipher;
    private final AfterCommitExecutor afterCommitExecutor;
    private final GoldenRebindReplayTrigger rebindReplayTrigger;

    public LlmConfigServiceImpl(JdbcTemplate jdbcTemplate, ConfigCipher configCipher,
                                AfterCommitExecutor afterCommitExecutor,
                                GoldenRebindReplayTrigger rebindReplayTrigger) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate);
        this.configCipher = configCipher;
        this.afterCommitExecutor = afterCommitExecutor;
        this.rebindReplayTrigger = rebindReplayTrigger;
    }

    @Override
    public List<LlmConfigInfo> list(Integer modelType) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM tp_llm_config");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        if (modelType != null) {
            sql.append(" WHERE model_type = :modelType");
            params.put("modelType", modelType);
        }
        sql.append(" ORDER BY is_default DESC, id");
        return namedJdbc.query(sql.toString(), params, this::mapInfo);
    }

    @Override
    public LlmConfigInfo get(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_llm_config WHERE id = ?",
                this::mapInfo, id).stream().findFirst().orElse(null);
    }

    @Override
    @Transactional
    @Audit(objectType = "llm_config", action = "create", objectId = "#result?.id", after = "#result")
    public LlmConfigInfo create(Long operatorId, LlmConfigInput input) {
        validate(input, null);
        Long exists = jdbcTemplate.query("SELECT id FROM tp_llm_config WHERE name = ?",
                rs -> rs.next() ? rs.getLong(1) : null, input.name().trim());
        if (exists != null) {
            throw new ServiceException("模型名称 " + input.name().trim() + " 已存在——请改用其它名称后重新保存；"
                    + "确需改动同名记录可前往「大模型配置」列表对该行点「编辑」", BizErrorCode.RESOURCE_CONFLICT);
        }
        if (input.isDefault() == 1) {
            clearDefaultInDomain(input.modelTypeOrDefault());
        }
        String storedKey = input.apiKey() == null || input.apiKey().isBlank()
                ? null : configCipher.encrypt(input.apiKey().trim());
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_llm_config (name, provider, model_name, api_key, base_url, status, is_default, description, created_by, prompt_limit_tokens, tokenizer_family, model_type, embedding_dimension)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, input.name().trim(), input.provider().trim(), input.modelName().trim(),
                storedKey, input.baseUrl(), input.status(), input.isDefault(), input.description(), operatorId,
                input.promptLimitOrDefault(), input.tokenizerFamilyOrDefault(),
                input.modelTypeOrDefault(), input.embeddingDimension());
        return get(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "llm_config", action = "update", objectId = "#id", after = "#result")
    public LlmConfigInfo update(Long operatorId, long id, LlmConfigInput input) {
        LlmConfigInfo current = get(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        if (current == null) {
            throw new ServiceException("大模型配置不存在（id " + id + "）——请刷新「大模型配置」列表后"
                    + "重新选择目标行再编辑", BizErrorCode.NOT_FOUND);
        }
        validate(input, id);
        Long exists = jdbcTemplate.query("SELECT id FROM tp_llm_config WHERE name = ? AND id <> ?",
                rs -> rs.next() ? rs.getLong(1) : null, input.name().trim(), id);
        if (exists != null) {
            throw new ServiceException("模型名称 " + input.name().trim() + " 已存在——请改回原名称或换新名称后重新保存；"
                    + "同名记录可在「大模型配置」列表核对后处理", BizErrorCode.RESOURCE_CONFLICT);
        }
        if (input.isDefault() == 1 && current.isDefault() != 1) {
            clearDefaultInDomain(input.modelTypeOrDefault());
        }
        String storedKey = input.apiKey() == null || input.apiKey().isBlank()
                ? current.apiKeyMasked() == null ? null
                : jdbcTemplate.queryForObject("SELECT api_key FROM tp_llm_config WHERE id = ?",
                        String.class, id)
                : configCipher.encrypt(input.apiKey().trim());
        jdbcTemplate.update("""
                UPDATE tp_llm_config SET name = ?, provider = ?, model_name = ?, api_key = ?,
                       base_url = ?, status = ?, is_default = ?, description = ?, updated_at = now(),
                       prompt_limit_tokens = ?, tokenizer_family = ?, model_type = ?, embedding_dimension = ?
                WHERE id = ?
                """, input.name().trim(), input.provider().trim(), input.modelName().trim(),
                storedKey, input.baseUrl(), input.status(), input.isDefault(), input.description(),
                input.promptLimitOrDefault(), input.tokenizerFamilyOrDefault(),
                input.modelTypeOrDefault(), input.embeddingDimension(), id);
        return get(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "llm_config", action = "delete", objectId = "#id")
    public void delete(long id) {
        if (get(id) == null) {
            throw new ServiceException("大模型配置不存在（id " + id + "）——请刷新「大模型配置」列表，"
                    + "确认该行是否已被删除", BizErrorCode.NOT_FOUND);
        }
        Integer refCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE llm_config_id = ?", Integer.class, id);
        if (refCount != null && refCount > 0) {
            throw new ServiceException("该模型已被 " + refCount + " 个项目选用，请先解除选用或停用——"
                    + "可在各项目「详情」页的「大模型」页签把「当前模型」改为其它模型并点「保存模型配置」",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        jdbcTemplate.update("DELETE FROM tp_llm_config WHERE id = ?", id);
    }

    @Override
    public ProjectLlmConfigInfo getProjectConfig(long projectId) {
        return jdbcTemplate.query("""
                SELECT p.llm_config_id, c.name
                FROM tp_project p LEFT JOIN tp_llm_config c ON c.id = p.llm_config_id
                WHERE p.id = ?
                """, (ResultSetExtractor<ProjectLlmConfigInfo>) rs -> {
            if (!rs.next()) {
                return null;
            }
            return new ProjectLlmConfigInfo(
                    (Long) rs.getObject(1), rs.getString(2));
        }, projectId);
    }

    @Override
    @Transactional
    @Audit(objectType = "project", action = "llm_config_change", objectId = "#projectId", after = "#result")
    public ProjectLlmConfigInfo updateProjectConfig(Long operatorId, long projectId, Long llmConfigId) {
        Boolean projectExists = jdbcTemplate.query("SELECT 1 FROM tp_project WHERE id = ?",
                (ResultSetExtractor<Boolean>) rs -> rs.next(), projectId);
        if (projectExists == null || !projectExists) {
            throw new ServiceException("项目不存在（id " + projectId + "）——请到「项目管理」确认该项目未被删除，"
                    + "重新进入该项目后再选择模型", BizErrorCode.NOT_FOUND);
        }
        // Route change detection for the golden replay fan-out :
        // effective route = explicit selection, else the system generation
        // default — captured before and after so bind/unbind/swap all compare
        // against what the project actually sent on the wire.
        String routeBefore = effectiveRouteName(projectId);
        if (llmConfigId == null) {
            jdbcTemplate.update("UPDATE tp_project SET llm_config_id = NULL, updated_at = now() WHERE id = ?",
                    projectId);
        } else {
            LlmConfigInfo model = get(llmConfigId);
            if (model == null) {
                throw new ServiceException("大模型配置不存在（id " + llmConfigId + "）——请在「大模型」页签的"
                        + "「当前模型」下拉重新选择清单内模型", BizErrorCode.NOT_FOUND);
            }
            if (model.status() != 1) {
                throw new ServiceException("该模型已停用，请启用后再选用——可在「大模型配置」页对该行点「编辑」，"
                        + "在弹窗把「状态」切为「启用」并保存", BizErrorCode.VALIDATION_ERROR);
            }
            if (model.modelType() == LlmConfigInput.TYPE_EMBEDDING) {
                throw new ServiceException("embedding 模型不可作为项目生成模型选用，请选择生成类型模型——"
                        + "「当前模型」下拉只选「模型类型」为「生成模型」的行"
                        + "（列表筛选处可选「生成模型」）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            jdbcTemplate.update("UPDATE tp_project SET llm_config_id = ?, updated_at = now() WHERE id = ?",
                    llmConfigId, projectId);
        }
        ProjectLlmConfigInfo result = getProjectConfig(projectId);
        String routeAfter = effectiveRouteName(projectId);
        if (!java.util.Objects.equals(routeBefore, routeAfter)) {
            // Fire after commit on a daemon thread (trigger owns the threading):
            // no model token burns inside the save transaction, and a dispatch
            // failure never propagates back into the human save (offline
            // replay posture, adjudication ).
            afterCommitExecutor.execute(() -> {
                try {
                    rebindReplayTrigger.onProjectRouteChanged(projectId, routeBefore, routeAfter);
                } catch (Exception dispatchFailure) {
                    log.warn("golden rebind replay dispatch failed for project {} ({})",
                            projectId, dispatchFailure.getMessage());
                }
            });
        }
        return result;
    }

    /** Route name the project actually sends on the wire (selection, else system default; null = none). */
    private String effectiveRouteName(long projectId) {
        LlmConfigBrief brief = resolveProjectBrief(projectId);
        return brief == null ? null : brief.name();
    }

    @Override
    public LlmConfigBrief resolveProjectBrief(long projectId) {
        Long configId = jdbcTemplate.query("SELECT llm_config_id FROM tp_project WHERE id = ?",
                (ResultSetExtractor<Long>) rs -> rs.next() ? (Long) rs.getObject(1) : null, projectId);
        String source = LlmConfigBrief.SOURCE_PROJECT;
        if (configId == null) {
            Long defaultId = jdbcTemplate.query("SELECT id FROM tp_llm_config WHERE is_default = 1 AND status = 1 AND model_type = " + LlmConfigInput.TYPE_GENERATION,
                    (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : null);
            configId = defaultId;
            source = LlmConfigBrief.SOURCE_DEFAULT;
        }
        if (configId == null) {
            return null;
        }
        Long finalConfigId = configId;
        String finalSource = source;
        return jdbcTemplate.query("SELECT " + BRIEF_COLUMNS + "\n"
                + "FROM tp_llm_config WHERE id = ?", (ResultSetExtractor<LlmConfigBrief>) rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapBrief(rs, finalSource);
        }, finalConfigId);
    }

    @Override
    public List<LlmConfigBrief> listEnabledBriefs() {
        return listEnabledBriefs(LlmConfigInput.TYPE_GENERATION);
    }

    @Override
    public List<LlmConfigBrief> listEnabledBriefs(Integer modelType) {
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(BRIEF_COLUMNS)
                .append("\nFROM tp_llm_config WHERE status = 1");
        if (modelType != null) {
            sql.append(" AND model_type = :modelType");
            params.put("modelType", modelType);
        }
        sql.append("\nORDER BY is_default DESC, id");
        return namedJdbc.query(sql.toString(), params,
                (rs, rowNum) -> mapBrief(rs, LlmConfigBrief.SOURCE_DEFAULT));
    }

    @Override
    public LlmConfigBrief resolveProjectEmbeddingBrief(long projectId) {
        Long configId = jdbcTemplate.query("SELECT embedding_config_id FROM tp_project WHERE id = ?",
                (ResultSetExtractor<Long>) rs -> rs.next() ? (Long) rs.getObject(1) : null, projectId);
        String source = LlmConfigBrief.SOURCE_PROJECT;
        if (configId == null) {
            Long defaultId = jdbcTemplate.query("SELECT id FROM tp_llm_config WHERE is_default = 1 AND status = 1 AND model_type = "
                            + LlmConfigInput.TYPE_EMBEDDING,
                    (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : null);
            configId = defaultId;
            source = LlmConfigBrief.SOURCE_DEFAULT;
        }
        if (configId == null) {
            return null;
        }
        Long finalConfigId = configId;
        String finalSource = source;
        return jdbcTemplate.query("SELECT " + BRIEF_COLUMNS + "\n"
                + "FROM tp_llm_config WHERE id = ?", (ResultSetExtractor<LlmConfigBrief>) rs -> {
            if (!rs.next()) {
                return null;
            }
            return mapBrief(rs, finalSource);
        }, finalConfigId);
    }

    
    private LlmConfigBrief mapBrief(ResultSet rs, String source) throws SQLException {
        String cipher = rs.getString(5);
        // cache_hint is SMALLINT 0/1; getObject keeps a null-safe read so a
        // column-less legacy row cannot be silently turned into "disabled".
        Object hint = rs.getObject(11);
        Boolean cacheHint = hint == null ? null : ((Number) hint).intValue() == 1;
        // structured_output shares the same null-safe read semantics:
        // a legacy column-less source reads null = capable, never "disabled".
        Object so = rs.getObject(12);
        Boolean structuredOutput = so == null ? null : ((Number) so).intValue() == 1;
        return new LlmConfigBrief(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), cipher == null ? null : configCipher.decrypt(cipher),
                rs.getString(6), source, rs.getInt(7), rs.getString(8),
                rs.getInt(9), (Integer) rs.getObject(10), cacheHint, structuredOutput);
    }

    private void validate(LlmConfigInput input, Long selfId) {
        if (input.name() == null || input.name().trim().isEmpty() || input.name().trim().length() > 100) {
            throw new ServiceException("模型名称不能为空且不超过 100 字（当前 "
                    + (input.name() == null ? 0 : input.name().trim().length()) + " 字）——"
                    + "请在「模型名称」改填 1~100 字的名称后重新保存（正确写法：如 deepseek-main）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.provider() == null || input.provider().trim().isEmpty()
                || input.provider().trim().length() > 50) {
            throw new ServiceException("供应商不能为空且不超过 50 字（当前 "
                    + (input.provider() == null ? 0 : input.provider().trim().length()) + " 字）——"
                    + "请在「供应商」改填不超过 50 字的取值后重新保存（正确写法：如 DeepSeek）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.modelName() == null || input.modelName().trim().isEmpty()
                || input.modelName().trim().length() > 100) {
            throw new ServiceException("模型名不能为空且不超过 100 字（当前 "
                    + (input.modelName() == null ? 0 : input.modelName().trim().length()) + " 字）——"
                    + "请在「模型名」改填不超过 100 字的取值（正确写法：如 deepseek-chat / gpt-4o-mini）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.status() != 0 && input.status() != 1) {
            throw new ServiceException("状态仅支持 0=停用 1=启用（当前 " + input.status() + "）——"
                    + "请改为两者之一（正确写法：0=停用、1=启用）", BizErrorCode.VALIDATION_ERROR);
        }
        int modelType = input.modelTypeOrDefault();
        if (modelType != LlmConfigInput.TYPE_GENERATION && modelType != LlmConfigInput.TYPE_EMBEDDING) {
            throw new ServiceException("模型类型仅支持 1=生成 2=embedding，当前 " + input.modelType()
                    + "——请改为两者之一（正确写法：1=生成模型、2=Embedding 模型）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (modelType == LlmConfigInput.TYPE_EMBEDDING) {
            if (input.embeddingDimension() == null) {
                throw new ServiceException("embedding 模型必须填写向量维度（embedding_dimension）——"
                        + "请在「向量维度」补填 1~16000 的整数，且与知识库向量表维度一致",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (input.embeddingDimension() < LlmConfigInput.EMBEDDING_DIM_MIN
                    || input.embeddingDimension() > LlmConfigInput.EMBEDDING_DIM_MAX) {
                throw new ServiceException("向量维度须为 " + LlmConfigInput.EMBEDDING_DIM_MIN + "~"
                        + LlmConfigInput.EMBEDDING_DIM_MAX + " 之间的整数，当前 "
                        + input.embeddingDimension()
                        + "——请把「向量维度」改为该区间内的整数后重新保存", BizErrorCode.VALIDATION_ERROR);
            }
            if (input.baseUrl() == null || input.baseUrl().isBlank()) {
                throw new ServiceException("embedding 模型必须填写 OpenAI 兼容端点基址（base_url）——"
                        + "请在「端点地址」补填基址（正确写法：https://api.deepseek.com/v1）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if (input.promptLimitTokens() != null && (input.promptLimitTokens()
                < LlmConfigInput.PROMPT_LIMIT_MIN || input.promptLimitTokens()
                > LlmConfigInput.PROMPT_LIMIT_MAX)) {
            throw new ServiceException("提示词上限须为 " + LlmConfigInput.PROMPT_LIMIT_MIN + "~"
                    + LlmConfigInput.PROMPT_LIMIT_MAX + " 之间的整数（token），当前 "
                    + input.promptLimitTokens()
                    + "——请把「提示词上限」改为该区间内的整数；留空则用默认 1048576",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.isDefault() != 0 && input.isDefault() != 1) {
            throw new ServiceException("默认标记仅支持 0/1（当前 " + input.isDefault() + "）——"
                    + "请改为两者之一（正确写法：0=非默认、1=设为默认）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.baseUrl() != null && input.baseUrl().length() > 500) {
            throw new ServiceException("端点地址过长（≤500 字，当前 " + input.baseUrl().length() + " 字）——"
                    + "请把「端点地址」缩短到 500 字以内后重新保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.description() != null && input.description().length() > 500) {
            throw new ServiceException("说明过长（≤500 字，当前 " + input.description().length() + " 字）——"
                    + "请把「说明」缩短到 500 字以内后重新保存", BizErrorCode.VALIDATION_ERROR);
        }
        String family = input.tokenizerFamily();
        if (family != null && !family.isBlank()
                && !LlmConfigInput.TOKENIZER_FAMILIES.contains(family.trim())) {
            throw new ServiceException("token 预估引擎族仅支持 "
                    + String.join("/", LlmConfigInput.TOKENIZER_FAMILIES) + "（auto=按模型名自动匹配），当前 "
                    + family
                    + "——请改为清单内取值之一后重新保存（正确写法：auto）", BizErrorCode.VALIDATION_ERROR);
        }
    }

 
    private void clearDefaultInDomain(int modelType) {
        jdbcTemplate.update("UPDATE tp_llm_config SET is_default = 0 WHERE is_default = 1 AND model_type = ?",
                modelType);
    }

    private LlmConfigInfo mapInfo(ResultSet rs, int rowNum) throws SQLException {
        return new LlmConfigInfo(
                rs.getLong("id"), rs.getString("name"), rs.getString("provider"),
                rs.getString("model_name"),
                rs.getString("api_key") == null ? null : ConfigCipher.MASK,
                rs.getString("base_url"), rs.getInt("status"), rs.getInt("is_default"),
                rs.getString("description"), rs.getInt("prompt_limit_tokens"),
                rs.getString("tokenizer_family"), rs.getInt("model_type"),
                (Integer) rs.getObject("embedding_dimension"));
    }
}

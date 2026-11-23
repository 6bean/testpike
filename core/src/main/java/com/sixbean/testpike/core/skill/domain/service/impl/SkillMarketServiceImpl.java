package com.sixbean.testpike.core.skill.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketDownloadView;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketItem;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketQuery;
import com.sixbean.testpike.core.skill.domain.component.SkillMarkdownParsed;
import com.sixbean.testpike.core.skill.domain.component.SkillPackSupport;
import com.sixbean.testpike.core.skill.domain.service.SkillMarketService;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import com.sixbean.testpike.core.skill.infrastructure.dataobject.SkillMarketRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


@Service
public class SkillMarketServiceImpl implements SkillMarketService {

 
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "s.id",
            "name", "s.name",
            "type", "s.type",
            "currentVersion", "s.current_version",
            "createdAt", "s.created_at",
            "downloadCount", "download_count",
            "refProjectCount", "ref_project_count",
            "call30d", "call30d_count");
    
    private static final int MAX_OFFSET = 100_000;
    
    private static final String CALL_WINDOW = "interval '30 days'";
 
    private static final String FORMAT_MD = "md";
    private static final String FORMAT_ZIP = "zip";
    
    private static final String SCENE = "skill-pack";

    private final NamedParameterJdbcTemplate named;
    private final SkillService skillService;
    private final SkillPackSupport skillPackSupport;
    private final StorageRouter storageRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillMarketServiceImpl(NamedParameterJdbcTemplate named, SkillService skillService,
                                  SkillPackSupport skillPackSupport, StorageRouter storageRouter) {
        this.named = named;
        this.skillService = skillService;
        this.skillPackSupport = skillPackSupport;
        this.storageRouter = storageRouter;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public PageResult<SkillMarketItem> marketList(SkillMarketQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "SKILL 市场");
        if (sortColumn == null) {
            sortColumn = "s.created_at";
        }
        String sortDir = "asc".equalsIgnoreCase(query.sortDir()) ? "ASC" : "DESC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE s.status = :published AND s.deleted = 0");
        params.addValue("published", ApprovalStatus.PUBLISHED.code());
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (s.name ILIKE :keyword OR COALESCE(s.body, '') ILIKE :keyword)");
            params.addValue("keyword", "%" + query.keyword().trim() + "%");
        }
        if (query.type() != null) {
            where.append(" AND s.type = :type");
            params.addValue("type", query.type());
        }
        if (query.tagId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM tp_skill_tag st"
                    + " WHERE st.skill_id = s.id AND st.tag_id = :tagId)");
            params.addValue("tagId", query.tagId());
        }
        Long total = named.queryForObject("SELECT count(*) FROM tp_skill s" + where, params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<SkillMarketItem> list = named.query(SELECT_MARKET + where
                        + " ORDER BY " + sortColumn + " " + sortDir + ", s.id DESC LIMIT :size OFFSET :offset",
                        params, new BeanPropertyRowMapper<>(SkillMarketRow.class))
                .stream().map(SkillMarketRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    
    private static final String SELECT_MARKET = """
            SELECT s.id, s.project_id, s.name, s.type, s.status, s.current_version, s.body,
                   s.download_count,
                   (SELECT count(*) FROM tp_project_skill ps
                     WHERE ps.skill_id = s.id)::int AS ref_project_count,
                   (SELECT count(*) FROM tp_skill_usage u
                     WHERE u.skill_id = s.id AND u.created_at >= now() - %s)::int AS call30d_count,
                   au.display_name AS author_name,
                   (SELECT string_agg(t.name, chr(31) ORDER BY t.name)
                      FROM tp_skill_tag st JOIN tp_tag_dict t ON t.id = st.tag_id
                     WHERE st.skill_id = s.id) AS tags_csv,
                   s.created_at
            FROM tp_skill s
            LEFT JOIN tp_user au ON au.id = s.created_by
            """.formatted(CALL_WINDOW);

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public SkillMarketDownloadView download(Long skillId, String format) {
        SkillInfo skill = skillService.get(skillId);
        if (!ApprovalStateMachine.isPublished(skill.status())) {
            throw new ServiceException("仅「已发布」的 SKILL 可下载（当前状态：" + skill.statusLabel()
                    + "）：未发布内容请到「SKILL 管理」页经「提交审批」发布后再下载，"
                    + "历史版本可在「版本历史」查看",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String fmt = format == null ? "" : format.trim().toLowerCase();
        if (!FORMAT_MD.equals(fmt) && !FORMAT_ZIP.equals(fmt)) {
            throw new ServiceException("下载格式不支持（当前传入：" + format + "）："
                    + "正确写法 format=md（单文件）或 zip（打包），"
                    + "请在「SKILL 市场」页点击「下载」选择格式",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String markdown = skillPackSupport.reconstructMarkdown(
                readFrontmatter(skill.frontmatterJson()), skillBody(skill.id()));
        String baseName = skill.name() + "." + fmt;
        byte[] content = FORMAT_ZIP.equals(fmt) ? SkillPackSupport.buildZip(skill.name() + ".md", markdown)
                : markdown.getBytes(StandardCharsets.UTF_8);

        String objectKey = StorageKeys.build(storageRouter.keyPrefix(),
                skill.projectId() == null ? 0L : skill.projectId(), SCENE, baseName);
        String contentType = FORMAT_ZIP.equals(fmt) ? "application/zip" : "text/markdown";
        storageRouter.systemProvider().put(objectKey, content, contentType);
        StorageGrant grant = storageRouter.systemProvider().presignGet(objectKey, storageRouter.urlTtl());
        named.update("UPDATE tp_skill SET download_count = download_count + 1 WHERE id = :id",
                new MapSqlParameterSource("id", skillId));
        return new SkillMarketDownloadView(skillId, fmt, baseName, grant.key(), grant.url());
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public SkillInfo importSkill(Long operatorId, Long projectId, String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new ServiceException("导入文件内容为空：请在「SKILL 市场」页点击「导入 SKILL」"
                    + "重新选择非空的 .md 或 .zip 文件（提交钮为「导入」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String name = filename == null ? "" : filename.toLowerCase();
        String markdown = name.endsWith("." + FORMAT_ZIP)
                ? skillPackSupport.extractFirstMarkdown(content)
                : new String(content, StandardCharsets.UTF_8);
        SkillMarkdownParsed parsed = skillPackSupport.parseMarkdown(markdown);
        String frontmatterJson = writeJson(parsed.frontmatter());
        int type = Integer.parseInt(parsed.frontmatter().get("type").toString().trim());
        return skillService.create(operatorId, new SkillCreateInput(projectId,
                parsed.frontmatter().get("name").toString().trim(), type, parsed.body(),
                frontmatterJson, Boolean.TRUE));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private String skillBody(Long skillId) {
        List<String> rows = named.queryForList(
                "SELECT body FROM tp_skill WHERE id = :id",
                new MapSqlParameterSource("id", skillId), String.class);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    private Map<String, Object> readFrontmatter(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ServiceException("SKILL frontmatter 存储内容异常（元数据反序列化失败）："
                    + "请到「SKILL 管理」页打开「编辑」重新保存以重建元数据，仍失败再联系管理员",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new ServiceException("frontmatter 序列化失败：" + e.getMessage()
                    + "——请减少 SKILL 元数据中的特殊字符，再到「SKILL 管理」页「编辑」重新保存重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}

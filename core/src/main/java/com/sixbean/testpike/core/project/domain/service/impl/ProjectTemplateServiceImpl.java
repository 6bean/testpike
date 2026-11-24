package com.sixbean.testpike.core.project.domain.service.impl;

import com.sixbean.testpike.core.project.domain.component.ProjectTemplateRefusals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateContent;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.api.dto.TemplateEnvItem;
import com.sixbean.testpike.core.project.api.dto.TemplateTagItem;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectTemplateService;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingItem;
import com.sixbean.testpike.core.skill.domain.service.ProjectSkillService;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Service
public class ProjectTemplateServiceImpl implements ProjectTemplateService {

    private static final String SELECT_COLUMNS =
            "id, name, industry, content, created_by, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProjectSkillService projectSkillService;
    private final TagService tagService;
    
    private final ProjectEnvService envService;

    public ProjectTemplateServiceImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                      ProjectSkillService projectSkillService, TagService tagService,
                                      @Lazy ProjectEnvService envService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.projectSkillService = projectSkillService;
        this.tagService = tagService;
        this.envService = envService;
    }

    @Override
    @Audit(objectType = "project_template", action = "create", objectId = "#result ?: #input.name()",
            after = "#result")
    public long create(Long operatorId, ProjectTemplateCreateInput input) {
        String name = trimToNull(input.name());
        if (name == null || name.length() > 100) {
            throw new ServiceException(ProjectTemplateRefusals.nameInvalid(input.name()), BizErrorCode.VALIDATION_ERROR);
        }
        String industry = trimToNull(input.industry());
        if (industry != null && industry.length() > 50) {
            throw new ServiceException(ProjectTemplateRefusals.industryTooLong(industry.length()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        validateContent(input.content());
        try {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO tp_project_template (name, industry, content, created_by)
                    VALUES (?, ?, ?::jsonb, ?) RETURNING id
                    """, Long.class, name, industry, toJson(input.content()), operatorId);
            return id == null ? 0L : id;
        } catch (DuplicateKeyException e) {
            throw new ServiceException("模板名称「%s」已存在，请更换", BizErrorCode.RESOURCE_CONFLICT, name);
        }
    }

    @Override
    @Audit(objectType = "project_template", action = "update", objectId = "#templateId", after = "#result")
    public ProjectTemplateInfo update(long templateId, ProjectTemplateCreateInput input) {
        get(templateId);
        String name = trimToNull(input.name());
        if (name == null || name.length() > 100) {
            throw new ServiceException(ProjectTemplateRefusals.nameInvalid(input.name()), BizErrorCode.VALIDATION_ERROR);
        }
        validateContent(input.content());
        String industry = trimToNull(input.industry());
        try {
            jdbcTemplate.update("""
                    UPDATE tp_project_template SET name = ?, industry = ?, content = ?::jsonb,
                    updated_at = now() WHERE id = ?
                    """, name, industry, toJson(input.content()), templateId);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("模板名称「%s」已存在，请更换", BizErrorCode.RESOURCE_CONFLICT, name);
        }
        return get(templateId);
    }

    @Override
    public ProjectTemplateInfo get(long templateId) {
        List<ProjectTemplateInfo> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project_template WHERE id = ?",
                this::mapRow, templateId);
        if (rows.isEmpty()) {
            throw new ServiceException(ProjectTemplateRefusals.templateMissing(templateId),
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public List<ProjectTemplateInfo> list() {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project_template ORDER BY id",
                this::mapRow);
    }

    @Override
    public void delete(long templateId) {
        get(templateId);
        jdbcTemplate.update("DELETE FROM tp_project_template WHERE id = ?", templateId);
    }

    @Override
    @Transactional
    public void applyToProject(long templateId, Long operatorId, long projectId) {
        ProjectTemplateInfo template = get(templateId);
        ProjectTemplateContent content = template.content();
        List<Long> skillIds = content.skillIdsOrEmpty();
        if (!skillIds.isEmpty()) {
            List<ProjectSkillBindingItem> items = new ArrayList<>(skillIds.size());
            for (Long skillId : skillIds) {
                items.add(new ProjectSkillBindingItem(skillId, null, null, null));
            }
            projectSkillService.saveBindings(operatorId, projectId, new ProjectSkillBindingInput(items, null));
        }
        for (TemplateTagItem tag : content.tagsOrEmpty()) {
            tagService.create(operatorId, new TagCreateInput(projectId, tag.name(), tag.type()));
        }
        for (TemplateEnvItem env : content.envsOrEmpty()) {
            envService.create(projectId, new ProjectEnvInput(env.name(), env.envType(),
                    env.baseUrl(), null, env.endTypes(), env.browsers(), null));
        }
        jdbcTemplate.update("UPDATE tp_project SET template_pack_id = ?, updated_at = now() WHERE id = ?",
                templateId, projectId);
    }

    @Override
    public void validateContent(ProjectTemplateContent content) {
        if (content == null) {
            throw new ServiceException(ProjectTemplateRefusals.contentMissing(), BizErrorCode.VALIDATION_ERROR);
        }
        Set<Long> seenSkills = new HashSet<>();
        int skillPos = 0;
        for (Long skillId : content.skillIdsOrEmpty()) {
            skillPos++;
            if (skillId == null) {
                throw new ServiceException(ProjectTemplateRefusals.skillIdNull(skillPos),
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!seenSkills.add(skillId)) {
                throw new ServiceException(ProjectTemplateRefusals.skillIdDuplicated(skillId),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        Set<String> seenTags = new HashSet<>();
        int tagPos = 0;
        for (TemplateTagItem tag : content.tagsOrEmpty()) {
            tagPos++;
            String name = tag.name() == null ? null : tag.name().trim();
            if (name == null || name.isEmpty()) {
                throw new ServiceException(ProjectTemplateRefusals.tagNameBlank(tagPos),
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!seenTags.add(name)) {
                throw new ServiceException(ProjectTemplateRefusals.tagNameDuplicated(name),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        Set<String> seenEnvs = new HashSet<>();
        int envPos = 0;
        for (TemplateEnvItem env : content.envsOrEmpty()) {
            envPos++;
            String name = env.name() == null ? null : env.name().trim();
            if (name == null || name.isEmpty()) {
                throw new ServiceException(ProjectTemplateRefusals.envNameBlank(envPos),
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!seenEnvs.add(name)) {
                throw new ServiceException(ProjectTemplateRefusals.envNameDuplicated(name),
                        BizErrorCode.VALIDATION_ERROR);
            }
            int envType = env.envType() == null ? 0 : env.envType();
            if (envType < 1 || envType > 3) {
                throw new ServiceException("示例环境类型仅支持 1=开发 2=测试 3=预发（禁止预置生产环境，"
                        + "生产配置请在项目环境中人工确认）", BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    private ProjectTemplateInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        long createdByRaw = rs.getLong("created_by");
        Long createdBy = rs.wasNull() ? null : createdByRaw;
        return new ProjectTemplateInfo(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("industry"),
                parseContent(rs.getString("content"), rs.getLong("id")),
                createdBy,
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private ProjectTemplateContent parseContent(String json, long templateId) {
        if (json == null || json.isBlank()) {
            return new ProjectTemplateContent(List.of(), List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, ProjectTemplateContent.class);
        } catch (Exception e) {
            throw new ServiceException(ProjectTemplateRefusals.contentParseFailed(templateId),
                    BizErrorCode.INTERNAL_ERROR);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(ProjectTemplateRefusals.contentSerializeFailed(),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

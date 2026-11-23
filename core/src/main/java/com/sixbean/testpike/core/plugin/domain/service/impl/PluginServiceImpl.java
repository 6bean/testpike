package com.sixbean.testpike.core.plugin.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.core.plugin.api.dto.AppliedProjectView;
import com.sixbean.testpike.core.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.core.plugin.api.dto.PluginView;
import com.sixbean.testpike.core.plugin.domain.component.PluginDescriptorSupport;
import com.sixbean.testpike.core.plugin.domain.service.PluginService;
import com.sixbean.testpike.core.plugin.infrastructure.dataobject.AppliedPluginRow;
import com.sixbean.testpike.core.plugin.infrastructure.dataobject.AppliedProjectRow;
import com.sixbean.testpike.core.plugin.infrastructure.dataobject.PluginRow;
import com.sixbean.testpike.core.project.domain.component.ProjectLockSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class PluginServiceImpl implements PluginService {

    
    static final int SOURCE_BUILTIN = 1;
    static final int SOURCE_UPLOAD = 2;

    private static final RowMapper<PluginRow> ROW = new BeanPropertyRowMapper<>(PluginRow.class);

    private final NamedParameterJdbcTemplate named;
    private final PluginDescriptorSupport descriptorSupport;
    private final ObjectMapper objectMapper;
    private final ProjectLockSupport projectLock;

    public PluginServiceImpl(NamedParameterJdbcTemplate named,
                             PluginDescriptorSupport descriptorSupport,
                             ObjectMapper objectMapper,
                             ProjectLockSupport projectLock) {
        this.named = named;
        this.descriptorSupport = descriptorSupport;
        this.objectMapper = objectMapper;
        this.projectLock = projectLock;
    }

    @Override
    public List<PluginView> list(String industry, Boolean enabled) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (industry != null && !industry.isBlank()) {
            where.append(" AND industry = :industry");
            params.addValue("industry", industry);
        }
        if (enabled != null) {
            where.append(" AND enabled = :enabled");
            params.addValue("enabled", enabled);
        }
        return query(where.toString(), params);
    }

    @Override
    public PluginView register(Long operatorId, String yamlContent) {
        Map<String, Object> manifest = descriptorSupport.parse(yamlContent);
        String name = (String) manifest.get("name");
        if (findByBuiltinName(name) != null) {
            throw new ServiceException("内置插件样例只读，禁止同名上传：" + name
                    + "（内置样例=迁移 seed 的 pos-retail / erp-supplychain）；如需定制，请在 plugin.yaml 的 name 字段另起名称后"
                    + "点「上传插件」重新注册，示例：pos-retail-custom",
                    BizErrorCode.FORBIDDEN);
        }
        try {
            named.queryForObject("""
                    INSERT INTO tp_plugin (name, industry, version, manifest, created_by)
                    VALUES (:name, :industry, :version, :manifest::jsonb, :operatorId)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("name", name)
                    .addValue("industry", manifest.get("industry"))
                    .addValue("version", manifest.get("version"))
                    .addValue("manifest", writeJson(manifest))
                    .addValue("operatorId", operatorId), Long.class);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("插件 " + name + " 版本 " + manifest.get("version")
                    + " 已存在（同名同版本冲突）；请在 plugin.yaml 的 version 字段升版本号后点「上传插件」重新上传，"
                    + "格式：语义版本 x.y.z，示例：1.1.0",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        PluginRow row = findByNameAndVersion(name, (String) manifest.get("version"));
        return toView(row);
    }

    @Override
    @Audit(objectType = "plugin", action = "enable", objectId = "#id", after = "#result")
    public PluginView setEnabled(Long operatorId, long id, boolean enabled) {
        int updated = named.update("""
                UPDATE tp_plugin SET enabled = :enabled, updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("enabled", enabled));
        if (updated == 0) {
            throw new ServiceException("插件不存在（id=" + id + "），"
                    + "请点「查询」回到「行业插件」列表核对该插件是否仍在列", BizErrorCode.NOT_FOUND);
        }
        return toView(findById(id));
    }

    @Override
    public PluginView detail(long id) {
        return toView(findById(id));
    }

    @Override
    @Transactional
    @Audit(objectType = "plugin", action = "update", objectId = "#id", after = "#result")
    public PluginView update(Long operatorId, long id, Integer revision, String yamlContent) {
        if (revision == null) {
            throw new ServiceException("编辑插件必须携带 revision（乐观锁版本号）；"
                    + "请先在「行业插件」列表点「查看」打开详情获取当前版本号，再点「编辑」提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        PluginRow current = findById(id);
        if (current.getSource() == SOURCE_BUILTIN) {
            throw new ServiceException("内置插件样例只读，禁止编辑：" + current.getName()
                    + "（内置样例=迁移 seed 的 pos-retail / erp-supplychain）；如需定制，请在「行业插件」页点「上传插件」"
                    + "另起名称注册新插件",
                    BizErrorCode.FORBIDDEN);
        }
        Map<String, Object> manifest = descriptorSupport.parse(yamlContent);
        String name = (String) manifest.get("name");
        String version = (String) manifest.get("version");
        if (findByBuiltinName(name) != null) {
            throw new ServiceException("内置插件样例只读，禁止同名改入：" + name
                    + "（内置样例=迁移 seed 的 pos-retail / erp-supplychain）；请在「编辑」表单把 name 字段另起名称后点「保存」，"
                    + "示例：pos-retail-custom",
                    BizErrorCode.FORBIDDEN);
        }
        PluginRow collision = findByNameAndVersion(name, version);
        if (collision != null && !collision.getId().equals(current.getId())) {
            throw new ServiceException("插件 " + name + " 版本 " + version
                    + " 已存在（同名同版本冲突）；请在「编辑」表单把 version 字段升版本号后点「保存」，"
                    + "格式：语义版本 x.y.z，示例：1.1.0",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        try {
            int updated = named.update("""
                    UPDATE tp_plugin
                       SET name = :name, industry = :industry, version = :version,
                           manifest = :manifest::jsonb, revision = revision + 1, updated_at = now()
                     WHERE id = :id AND revision = :expected
                    """, new MapSqlParameterSource()
                    .addValue("name", name)
                    .addValue("industry", manifest.get("industry"))
                    .addValue("version", version)
                    .addValue("manifest", writeJson(manifest))
                    .addValue("id", id)
                    .addValue("expected", revision));
            if (updated == 0) {
                throw versionConflict(findById(id));
            }
        } catch (DuplicateKeyException e) {
            throw new ServiceException("插件 " + name + " 版本 " + version
                    + " 已存在（同名同版本冲突）；请在「编辑」表单把 version 字段升版本号后点「保存」，"
                    + "格式：语义版本 x.y.z，示例：1.1.0",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return toView(findById(id));
    }

    private static final RowMapper<AppliedProjectRow> APPLIED_PROJECT_ROW =
            new BeanPropertyRowMapper<>(AppliedProjectRow.class);

    @Override
    public List<AppliedProjectView> appliedProjects(long pluginId) {
        findById(pluginId);
        return named.query("""
                        SELECT pp.project_id, p.name AS project_name,
                               to_char(pp.applied_at, 'YYYY-MM-DD HH24:MI:SS') AS applied_at
                          FROM tp_project_plugin pp
                          JOIN tp_project p ON p.id = pp.project_id
                         WHERE pp.plugin_id = :pluginId
                         ORDER BY pp.applied_at, pp.id
                        """,
                new MapSqlParameterSource("pluginId", pluginId), APPLIED_PROJECT_ROW)
                .stream().map(r -> new AppliedProjectView(
                        r.getProjectId(), r.getProjectName(), r.getAppliedAt())).toList();
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    @Audit(objectType = "project_plugin", action = "apply", objectId = "#projectId", after = "#result")
    public PluginApplyView apply(Long operatorId, long projectId, long pluginId, boolean confirm) {
        projectLock.lockProjectOrThrow(projectId);
        PluginRow plugin = findById(pluginId);
        if (!plugin.isEnabled()) {
            throw new ServiceException("插件 " + plugin.getName() + " 已停用，无法装配"
                    + "（停用=新装配失效，不影响已装配项目）；如需用，请在「行业插件」列表把该行「启用」列开关切到开，"
                    + "再回到「插件装配」页签重新装配", BizErrorCode.FORBIDDEN);
        }
        Integer applied = named.queryForObject("""
                SELECT count(*) FROM tp_project_plugin
                 WHERE project_id = :projectId AND plugin_id = :pluginId
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId).addValue("pluginId", pluginId), Integer.class);
        if (applied != null && applied > 0) {
            throw new ServiceException("该项目已装配插件 " + plugin.getName()
                    + "（重复装配幂等拒）；如需更新参数，请到「项目管理」该项目的「插件装配」页签点「卸载」后"
                    + "再点「装配插件」，卸载不会删除已生成资产",
                    BizErrorCode.RESOURCE_CONFLICT);
        }

        Map<String, Object> manifest = readJson(plugin.getManifest());
        Map<String, Object> snapshot = asMap(manifest.get("param_template"));

        List<String> notes = new ArrayList<>();
        List<String> mounted = new ArrayList<>();
        for (String skillName : asStringList(manifest.get("default_skills"))) {
            Long skillId = findSkillIdByName(skillName);
            if (skillId == null) {
                notes.add("SKILL 不存在，跳过挂载：" + skillName);
                continue;
            }
            named.update("""
                    INSERT INTO tp_project_skill (project_id, skill_id, role, enabled)
                    VALUES (:projectId, :skillId, 3, 1)
                    ON CONFLICT (project_id, skill_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId).addValue("skillId", skillId));
            mounted.add(skillName);
        }
        String contentPack = (String) manifest.get("content_pack");
        notes.add("H7 未就绪，内容包 " + contentPack + " 装载跳过");
        notes.add("项目类型维度未实现，兼容校验跳过（confirm=" + confirm + " 预定义参数，本批不生效）");

        try {
            named.update("""
                    INSERT INTO tp_project_plugin (project_id, plugin_id, param_snapshot, applied_by)
                    VALUES (:projectId, :pluginId, :snapshot::jsonb, :operatorId)
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("pluginId", pluginId)
                    .addValue("snapshot", writeJson(snapshot))
                    .addValue("operatorId", operatorId));
        } catch (DuplicateKeyException e) {
            throw new ServiceException("该项目已装配插件 " + plugin.getName()
                    + "（重复装配幂等拒）；如需更新参数，请到「项目管理」该项目的「插件装配」页签点「卸载」后"
                    + "再点「装配插件」，卸载不会删除已生成资产",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return new PluginApplyView(projectId, pluginId, plugin.getName(), snapshot, mounted, notes);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    @Audit(objectType = "project_plugin", action = "uninstall", objectId = "#projectId")
    public void uninstall(Long operatorId, long projectId, long pluginId) {
        projectLock.lockProjectOrThrow(projectId);
        PluginRow plugin = findById(pluginId);
        int removed = named.update("""
                DELETE FROM tp_project_plugin
                 WHERE project_id = :projectId AND plugin_id = :pluginId
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId).addValue("pluginId", pluginId));
        if (removed == 0) {
            throw new ServiceException("该项目未装配插件 " + plugin.getName()
                    + "（无可卸载记录）；如需装配，请到「项目管理」该项目的「插件装配」页签点「装配插件」",
                    BizErrorCode.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private static final RowMapper<AppliedPluginRow> APPLIED_ROW =
            new BeanPropertyRowMapper<>(AppliedPluginRow.class);

    @Override
    public List<AppliedPluginView> listApplied(long projectId) {
        return named.query("""
                        SELECT pp.plugin_id, p.name AS plugin_name, p.enabled,
                               pp.param_snapshot::text AS param_snapshot,
                               to_char(pp.applied_at, 'YYYY-MM-DD HH24:MI:SS') AS applied_at
                          FROM tp_project_plugin pp
                          JOIN tp_plugin p ON p.id = pp.plugin_id
                         WHERE pp.project_id = :projectId
                         ORDER BY pp.applied_at, pp.id
                        """,
                new MapSqlParameterSource("projectId", projectId), APPLIED_ROW)
                .stream().map(this::toAppliedView).toList();
    }

    private AppliedPluginView toAppliedView(AppliedPluginRow row) {
        return new AppliedPluginView(row.getPluginId(), row.getPluginName(), row.isEnabled(),
                readJson(row.getParamSnapshot()), row.getAppliedAt());
    }

    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Long findSkillIdByName(String name) {
        List<Long> ids = named.queryForList(
                "SELECT id FROM tp_skill WHERE name = :name AND deleted = 0",
                new MapSqlParameterSource("name", name), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private static final String COLUMNS =
            "id, name, industry, version, enabled, source, revision, manifest::text AS manifest";

    private List<PluginView> query(String where, MapSqlParameterSource params) {
        return named.query("SELECT " + COLUMNS + " FROM tp_plugin" + where + " ORDER BY id",
                params, ROW).stream().map(this::toView).toList();
    }

    private PluginRow findFirst(String where, MapSqlParameterSource params) {
        List<PluginRow> rows = named.query("SELECT " + COLUMNS + " FROM tp_plugin" + where,
                params, ROW);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PluginRow findByBuiltinName(String name) {
        return findFirst(" WHERE name = :name AND source = :source",
                new MapSqlParameterSource().addValue("name", name).addValue("source", SOURCE_BUILTIN));
    }

    private PluginRow findByNameAndVersion(String name, String version) {
        return findFirst(" WHERE name = :name AND version = :version",
                new MapSqlParameterSource().addValue("name", name).addValue("version", version));
    }

    private PluginRow findById(long id) {
        PluginRow row = findFirst(" WHERE id = :id", new MapSqlParameterSource("id", id));
        if (row == null) {
            throw new ServiceException("插件不存在（id=" + id + "），"
                    + "请点「查询」回到「行业插件」列表核对该插件是否仍在列", BizErrorCode.NOT_FOUND);
        }
        return row;
    }

    private PluginView toView(PluginRow row) {
        return new PluginView(row.getId(), row.getName(), row.getIndustry(), row.getVersion(),
                row.isEnabled(), row.getSource(), readJson(row.getManifest()), row.getRevision());
    }

    
    private ServiceException versionConflict(PluginRow current) {
        return new ServiceException("插件已被他人修改（当前版本 "
                + current.getRevision() + "），请点「取消编辑」后在「行业插件」列表重新点「编辑」打开最新内容再提交",
                BizErrorCode.VERSION_CONFLICT);
    }

    private String writeJson(Map<String, Object> manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("插件描述符序列化失败", e);
        }
    }

    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("插件 manifest 反序列化失败（id 数据异常）", e);
        }
    }
}

package com.sixbean.testpike.core.apitest.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.StepAliasContract;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInfo;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInput;
import com.sixbean.testpike.core.apitest.domain.service.StepAliasService;
import com.sixbean.testpike.core.apitest.infrastructure.dataobject.StepAliasRow;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class StepAliasServiceImpl implements StepAliasService {

    private static final int MAX_ALIAS = 64;
    private static final int MAX_TARGET = 256;
    private static final Set<String> KINDS = Set.of("path", "step");

    private final NamedParameterJdbcTemplate namedJdbc;

    public StepAliasServiceImpl(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    @Override
    @Audit(objectType = "stepAlias", action = "create", after = "#result")
    public StepAliasInfo create(Long operatorId, StepAliasInput input) {
        if (input == null || input.projectId() == null || input.projectId() <= 0) {
            throw new ServiceException("别名未挂到项目（创建请求里 projectId 为空）——请先在顶栏项目栏选定目标项目，再到接口测试页在步骤列表点「重命名」重新登记别名", BizErrorCode.VALIDATION_ERROR);
        }
        String violation = StepAliasContract.violation(input.aliasName());
        if (violation != null) {
            throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
        }
        if (input.aliasName().length() > MAX_ALIAS) {
            throw new ServiceException("别名「" + input.aliasName() + "」有 " + input.aliasName().length()
                            + " 字，超过上限 " + MAX_ALIAS + " 字——请精简「别名」输入框内容后重新保存（入口：接口测试页 → 步骤列表「重命名」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String kind = requireKind(input.kind());
        String target = requireTarget(input.target());
        requireProject(input.projectId());
        checkNameUnique(input.projectId(), input.aliasName(), 0L);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", input.projectId())
                .addValue("aliasName", input.aliasName())
                .addValue("kind", kind)
                .addValue("target", target)
                .addValue("ownerRef", trimToNull(input.ownerRef()))
                .addValue("operatorId", operatorId);
        Long id = namedJdbc.queryForObject("""
                INSERT INTO tp_step_alias (project_id, alias_name, kind, target, owner_ref, created_by)
                VALUES (:projectId, :aliasName, :kind, :target, :ownerRef, :operatorId) RETURNING id
                """, params, Long.class);
        return new StepAliasInfo(id, input.projectId(), input.aliasName(), kind, target,
                trimToNull(input.ownerRef()));
    }

    @Override
    @Audit(objectType = "stepAlias", action = "update", objectId = "#aliasId")
    public StepAliasInfo update(Long operatorId, long aliasId, String kind, String target, String ownerRef) {
        StepAliasRow current = requireAlias(aliasId);
        String newKind = kind == null || kind.isBlank() ? current.getKind() : requireKind(kind);
        String newTarget = target == null || target.isBlank() ? current.getTarget() : requireTarget(target);
        namedJdbc.update("""
                UPDATE tp_step_alias
                   SET kind = :kind, target = :target, owner_ref = :ownerRef, updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("kind", newKind)
                .addValue("target", newTarget)
                .addValue("ownerRef", trimToNull(ownerRef))
                .addValue("id", aliasId));
        return new StepAliasInfo(aliasId, current.getProjectId(), current.getAliasName(),
                newKind, newTarget, trimToNull(ownerRef));
    }

    @Override
    @Audit(objectType = "stepAlias", action = "delete", objectId = "#aliasId")
    public void delete(Long operatorId, long aliasId) {
        requireAlias(aliasId);
        namedJdbc.update("DELETE FROM tp_step_alias WHERE id = :id",
                new MapSqlParameterSource("id", aliasId));
    }

    @Override
    public List<StepAliasInfo> list(long projectId) {
        return namedJdbc.query("""
                SELECT id, project_id AS projectId, alias_name AS aliasName, kind, target, owner_ref AS ownerRef
                  FROM tp_step_alias
                 WHERE project_id = :projectId
                 ORDER BY alias_name
                """, new MapSqlParameterSource("projectId", projectId),
                new BeanPropertyRowMapper<>(StepAliasRow.class))
                .stream()
                .map(row -> new StepAliasInfo(row.getId(), row.getProjectId(), row.getAliasName(),
                        row.getKind(), row.getTarget(), row.getOwnerRef()))
                .collect(Collectors.toList());
    }

    @Override
    public StepAliasInfo find(long aliasId) {
        StepAliasRow row = requireAlias(aliasId);
        return new StepAliasInfo(row.getId(), row.getProjectId(), row.getAliasName(),
                row.getKind(), row.getTarget(), row.getOwnerRef());
    }

    @Override
    public Set<String> aliasNames(long projectId) {
        return list(projectId).stream()
                .filter(info -> StepAliasContract.violation(info.aliasName()) == null)
                .map(StepAliasInfo::aliasName)
                .collect(Collectors.toSet());
    }

    private String requireKind(String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new ServiceException("别名种类" + (kind == null ? "未填写" : "「" + kind + "」")
                    + "不在允许范围——仅支持 path=路径别名（JsonPath→易读名）与 step=步骤别名（步骤→业务名），请在登记弹窗的「类型」下拉选择 path 或 step 后重新保存（入口：接口测试页 → 步骤列表「重命名」）", BizErrorCode.VALIDATION_ERROR);
        }
        return kind;
    }

    private String requireTarget(String target) {
        if (target == null || target.isBlank()) {
            throw new ServiceException("该别名缺少指向的寻址串——请填写「寻址串」：step 别名=步骤号段（如 3.orderNo）、path 别名=池键段（如 订单号），然后重新保存（入口：接口测试页 → 步骤列表「重命名」）", BizErrorCode.VALIDATION_ERROR);
        }
        if (target.length() > MAX_TARGET) {
            throw new ServiceException("寻址串「" + target.trim() + "」有 " + target.length()
                    + " 字，超过上限 " + MAX_TARGET + " 字——请只保留单个步骤号段或池键段"
                    + "（正确写法示例「3.orderNo」）后重新保存（入口：接口测试页 → 步骤列表「重命名」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return target.trim();
    }

    private void checkNameUnique(long projectId, String aliasName, long excludeId) {
        Long dup = namedJdbc.queryForObject("""
                SELECT count(*) FROM tp_step_alias
                 WHERE project_id = :projectId AND alias_name = :aliasName AND id <> :excludeId
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("aliasName", aliasName)
                .addValue("excludeId", excludeId), Long.class);
        if (dup != null && dup > 0) {
            throw new ServiceException("项目里已有同名别名「" + aliasName
                    + "」——同一项目内别名必须唯一，引用才能确定指向；请换一个业务名后保存，当前已登记别名可在引用下拉「项目已登记的别名」分组查看",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    private void requireProject(long projectId) {
        Long exists = namedJdbc.queryForObject("SELECT count(*) FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", projectId), Long.class);
        if (exists == null || exists == 0) {
            throw new ServiceException("项目 " + projectId + " 不存在（可能已被删除）——请先通过顶栏「选择项目」改选一个现存项目，再登记或使用别名", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private StepAliasRow requireAlias(long aliasId) {
        List<StepAliasRow> rows = namedJdbc.query("""
                SELECT id, project_id AS projectId, alias_name AS aliasName, kind, target, owner_ref AS ownerRef
                  FROM tp_step_alias WHERE id = :id
                """, new MapSqlParameterSource("id", aliasId),
                new BeanPropertyRowMapper<>(StepAliasRow.class));
        if (rows.isEmpty()) {
            throw new ServiceException("要操作的别名不存在（可能已被删除，编号 " + aliasId + "）——请到接口测试页在步骤列表点「重命名」重新登记别名，或把引用改为项目里仍在的别名（引用下拉「项目已登记的别名」分组可见）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

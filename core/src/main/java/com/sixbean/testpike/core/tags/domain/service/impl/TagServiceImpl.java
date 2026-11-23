package com.sixbean.testpike.core.tags.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.tags.api.dto.CaseTagSetInput;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.api.dto.TagMergeInput;
import com.sixbean.testpike.core.tags.api.dto.TagRenameInput;
import com.sixbean.testpike.core.tags.api.dto.TagStats;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class TagServiceImpl implements TagService {

    private static final int TYPE_MODULE = 1;
    private static final int TYPE_FUNCTION = 2;
    private static final int TYPE_PRIORITY = 3;
    private static final int TYPE_FREE = 4;
 
 private static final int TYPE_POOL = 7;

 /** Server-side sort whitelist (tag list; frontend column props map onto
  * these keys via SORT_KEY_MAP). Values are physical columns — never user
  * input. case_count is the projected aggregate alias (same query level,
  * allowed by Postgres ORDER BY), which the plan-list precedent already uses. */
 private static final Map<String, String> SORT_COLUMNS = Map.of(
         "name", "td.name",
         "type", "td.type",
         "created_at", "td.created_at",
         "case_count", "case_count");

    private final JdbcTemplate jdbcTemplate;

    public TagServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Audit(objectType = "tag", action = "create", after = "#result")
    public TagInfo create(Long operatorId, TagCreateInput input) {
        if (input == null || input.projectId() <= 0 || input.name() == null
                || input.name().trim().isEmpty()) {
            throw new ServiceException("标签名称不能为空，请在「新建标签」弹窗填写名称后再提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String name = input.name().trim();
        if (name.length() > 50) {
            throw new ServiceException("标签名称最长 50 字，请在「新建标签」弹窗缩短名称后再提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int type = input.type() == null ? TYPE_FREE : input.type();
        validateType(type);
        checkNameUnique(input.projectId(), name, 0L);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_tag_dict (project_id, name, type, created_by)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, input.projectId(), name, type, operatorId);
        return new TagInfo(id, input.projectId(), name, type, 0L);
    }

    @Override
    @Audit(objectType = "tag", action = "update", objectId = "#tagId", after = "#result")
    public TagInfo rename(Long operatorId, long tagId, TagRenameInput input) {
        if (input == null || input.newName() == null || input.newName().trim().isEmpty()) {
            throw new ServiceException("标签名称不能为空，请在「标签改名」弹窗填写新名称后再保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String newName = input.newName().trim();
        if (newName.length() > 50) {
            throw new ServiceException("标签名称最长 50 字，请在「标签改名」弹窗缩短名称后再保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        TagInfo current = requireTag(tagId);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(Map.of(
                "id", current.id(), "name", current.name(), "type", current.type())));
        if (newName.equals(current.name())) {
            return current;
        }
        checkNameUnique(current.projectId(), newName, tagId);
        // AM batch a rename is a content change — bump the row
        // version so dialog drafts anchored on the old value re-anchor honestly.
        jdbcTemplate.update("UPDATE tp_tag_dict SET name = ?, version = version + 1 WHERE id = ?",
                newName, tagId);
        return new TagInfo(tagId, current.projectId(), newName, current.type(), current.caseCount());
    }

    @Override
    @Transactional
    @Audit(objectType = "tag", action = "merge", objectId = "#input.fromTagId()")
    public void merge(Long operatorId, TagMergeInput input) {
        if (input == null || input.fromTagId() <= 0 || input.toTagId() <= 0) {
            throw new ServiceException("合并参数不完整，请在「合并标签」弹窗同时选好「被合并标签」与「目标标签」后再确认",
                BizErrorCode.VALIDATION_ERROR);
        }
        if (input.fromTagId() == input.toTagId()) {
            throw new ServiceException("不能合并到自身标签，请在「合并标签」弹窗将「被合并标签」与「目标标签」选为两个不同标签",
                BizErrorCode.VALIDATION_ERROR);
        }
        TagInfo from = requireTag(input.fromTagId());
        TagInfo to = requireTag(input.toTagId());
        if (from.projectId() != to.projectId()) {
            throw new ServiceException("仅可合并同一项目内的标签，请在顶栏切换到标签所在项目后再到「用例标签」页操作",
                BizErrorCode.VALIDATION_ERROR);
        }
        jdbcTemplate.update("""
                DELETE FROM tp_case_tag WHERE tag_id = ?
                  AND case_id IN (SELECT case_id FROM tp_case_tag WHERE tag_id = ?)
                """, input.toTagId(), input.fromTagId());
        jdbcTemplate.update("UPDATE tp_case_tag SET tag_id = ? WHERE tag_id = ?",
                input.toTagId(), input.fromTagId());
        // Skill bindings and execution-batch pool references cite the same
        // dictionary row: re-point them in the same overlap-then-move shape
        // before the source row is deleted. Otherwise the tp_skill_tag
        // foreign key rejects the merge with an opaque 500 and a batch keeps
        // a dangling pool tag id that makes the dispatch pool unreadable.
        jdbcTemplate.update("""
                DELETE FROM tp_skill_tag WHERE tag_id = ?
                  AND skill_id IN (SELECT skill_id FROM tp_skill_tag WHERE tag_id = ?)
                """, input.toTagId(), input.fromTagId());
        jdbcTemplate.update("UPDATE tp_skill_tag SET tag_id = ? WHERE tag_id = ?",
                input.toTagId(), input.fromTagId());
        jdbcTemplate.update("UPDATE tp_exec_batch SET pool_tag_id = ? WHERE pool_tag_id = ?",
                input.toTagId(), input.fromTagId());
        jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE id = ?", input.fromTagId());
        // AM batch the merge rewrites the surviving row's
        // associations — bump its version so drafts anchored before the merge
        // see the row as changed (the from-row is gone, nothing to anchor).
        jdbcTemplate.update("UPDATE tp_tag_dict SET version = version + 1 WHERE id = ?",
                input.toTagId());
    }

    @Override
    @Audit(objectType = "tag", action = "delete", objectId = "#tagId")
    public void delete(Long operatorId, long tagId) {
        TagInfo current = requireTag(tagId);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(Map.of(
                "id", current.id(), "name", current.name(), "type", current.type())));
        if (current.caseCount() > 0) {
            throw new ServiceException(
                    "标签「" + current.name() + "」已被 " + current.caseCount()
                            + " 条用例引用，无法删除；请在「用例标签」页点击「合并」将其并入其他标签后再删除",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        Long batchRefs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE pool_tag_id = ?", Long.class, tagId);
        if (batchRefs != null && batchRefs > 0) {
            throw new ServiceException(
                    "标签「" + current.name() + "」已被 " + batchRefs
                            + " 个批次选为资源池，无法删除；请到「执行批次」页调整该批次的资源池后再删除",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        // A tag still cited by skill assets cannot be dropped: the tp_skill_tag
        // foreign key would reject the delete as an opaque 500. Refuse with a
        // readable conflict naming the referencing family, the count and the
        // merge path, mirroring the case-count and pool-count guards above.
        Long skillRefs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_skill_tag WHERE tag_id = ?", Long.class, tagId);
        if (skillRefs != null && skillRefs > 0) {
            throw new ServiceException(
                    "标签「" + current.name() + "」已被 " + skillRefs
                            + " 个技能引用，无法删除；请到「SKILL 管理」页调整技能的标签引用，或在「用例标签」页点击「合并」并入其他标签后再删除",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE id = ?", tagId);
    }

    @Override
    public List<TagInfo> list(long projectId) {
        return list(projectId, null, null);
    }

    @Override
    public List<TagInfo> list(long projectId, String sortBy, String sortDir) {
        // Sort keys must resolve to a physical column (or the aggregate alias)
        // through the shared whitelist — user input never reaches the SQL string
        // (SortColumnPolicy.require, same posture as the user/requirement domains;
        // off-list key = fail-closed 400). case_count is the projected count(*)
        // alias, which Postgres allows in ORDER BY at the same query level.
        String sortColumn = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "标签");
        String order = sortColumn == null
                ? " ORDER BY td.id"
                : " ORDER BY " + sortColumn
                        + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC") + ", td.id ASC";
        return jdbcTemplate.query("""
                SELECT td.id, td.project_id, td.name, td.type,
                       (SELECT count(*) FROM tp_case_tag ct WHERE ct.tag_id = td.id) AS case_count
                FROM tp_tag_dict td
                WHERE td.project_id = ?
                """ + order,
                (rs, rowNum) -> new TagInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("name"), rs.getInt("type"), rs.getLong("case_count")), projectId);
    }

    @Override
    public List<TagInfo> listVisible(List<Long> projectIds) {
        List<Object> ids = positiveDistinctIds(projectIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbcTemplate.query("""
                SELECT td.id, td.project_id, td.name, td.type,
                       (SELECT count(*) FROM tp_case_tag ct WHERE ct.tag_id = td.id) AS case_count
                FROM tp_tag_dict td
                WHERE td.project_id IN (""" + marks + ")"
                + " ORDER BY td.project_id, td.id",
                (rs, rowNum) -> new TagInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("name"), rs.getInt("type"), rs.getLong("case_count")), ids.toArray());
    }

    @Override
    public TagInfo get(long tagId) {
        return requireTag(tagId);
    }

    @Override
    public Integer currentVersion(long tagId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT version FROM tp_tag_dict WHERE id = ?", Integer.class, tagId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<TagStats> stats(long projectId) {
        return jdbcTemplate.query("""
                SELECT td.id, td.name, td.type,
                       count(ct.id) AS case_count,
                       count(*) FILTER (WHERE tc.last_exec_result = 3) AS pass_count
                FROM tp_tag_dict td
                LEFT JOIN tp_case_tag ct ON ct.tag_id = td.id
                LEFT JOIN tp_test_case tc ON tc.id = ct.case_id AND tc.deleted = 0
                WHERE td.project_id = ?
                GROUP BY td.id, td.name, td.type
                ORDER BY td.id
                """, (rs, rowNum) -> {
            long total = rs.getLong("case_count");
            long pass = rs.getLong("pass_count");
            double rate = total == 0 ? 0.0 : Math.round(pass * 1000.0 / total) / 10.0;
            return new TagStats(rs.getLong("id"), rs.getString("name"), rs.getInt("type"),
                    total, pass, rate);
        }, projectId);
    }

    @Override
    public List<TagStats> statsVisible(List<Long> projectIds) {
        List<Object> ids = positiveDistinctIds(projectIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbcTemplate.query("""
                SELECT td.id, td.name, td.type,
                       count(ct.id) AS case_count,
                       count(*) FILTER (WHERE tc.last_exec_result = 3) AS pass_count
                FROM tp_tag_dict td
                LEFT JOIN tp_case_tag ct ON ct.tag_id = td.id
                LEFT JOIN tp_test_case tc ON tc.id = ct.case_id AND tc.deleted = 0
                WHERE td.project_id IN (""" + marks + ")"
                + " GROUP BY td.id, td.name, td.type"
                + " ORDER BY td.project_id, td.id",
                (rs, rowNum) -> {
            long total = rs.getLong("case_count");
            long pass = rs.getLong("pass_count");
            double rate = total == 0 ? 0.0 : Math.round(pass * 1000.0 / total) / 10.0;
            return new TagStats(rs.getLong("id"), rs.getString("name"), rs.getInt("type"),
                    total, pass, rate);
        }, ids.toArray());
    }

    
    private List<Object> positiveDistinctIds(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        List<Object> ids = new ArrayList<>();
        for (Long id : projectIds) {
            if (id != null && id > 0 && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    @Transactional
    @Audit(objectType = "case_tag", action = "set", objectId = "#caseId")
    public List<TagInfo> setCaseTags(Long operatorId, long caseId, CaseTagSetInput input) {
        if (input == null || input.projectId() <= 0) {
            throw new ServiceException("项目参数缺失，请在顶栏选择项目后再打开「设置用例标签」重试",
                BizErrorCode.VALIDATION_ERROR);
        }
        Long caseProjectId = jdbcTemplate.queryForObject(
                "SELECT project_id FROM tp_test_case WHERE id = ? AND deleted = 0",
                Long.class, caseId);
        if (caseProjectId == null) {
            throw new ServiceException("用例不存在或已被删除，请刷新列表后在「测试用例」页重新选择用例",
                BizErrorCode.NOT_FOUND);
        }
        if (caseProjectId != input.projectId()) {
            throw new ServiceException("用例不属于该项目，请在顶栏切换到用例所在项目后再用「设置用例标签」设置",
                BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> tagIds = input.tagIds() == null ? List.of() : input.tagIds();
        if (!tagIds.isEmpty()) {
            Long matched = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM tp_tag_dict
                    WHERE project_id = ? AND id IN (%s)
                    """.formatted(placeholders(tagIds.size())),
                    Long.class, concat(List.of(input.projectId()), tagIds).toArray());
            if (matched == null || matched != tagIds.size()) {
                throw new ServiceException("存在不属于该项目的标签，请在「设置用例标签」弹窗重新勾选当前项目下的标签",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        jdbcTemplate.update("DELETE FROM tp_case_tag WHERE case_id = ?", caseId);
        if (!tagIds.isEmpty()) {
            StringBuilder insert = new StringBuilder(
                    "INSERT INTO tp_case_tag (case_id, tag_id) VALUES ");
            List<Object> insertArgs = new ArrayList<>();
            for (int i = 0; i < tagIds.size(); i++) {
                if (i > 0) {
                    insert.append(", ");
                }
                insert.append("(?, ?)");
                insertArgs.add(caseId);
                insertArgs.add(tagIds.get(i));
            }
            insert.append(" ON CONFLICT (case_id, tag_id) DO NOTHING");
            jdbcTemplate.update(insert.toString(), insertArgs.toArray());
        }
        return caseTags(caseId);
    }

    @Override
    public List<TagInfo> caseTags(long caseId) {
        return jdbcTemplate.query("""
                SELECT td.id, td.project_id, td.name, td.type,
                       (SELECT count(*) FROM tp_case_tag ct WHERE ct.tag_id = td.id) AS case_count
                FROM tp_case_tag ct
                JOIN tp_tag_dict td ON ct.tag_id = td.id
                WHERE ct.case_id = ?
                ORDER BY td.id
                """, (rs, rowNum) -> new TagInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("name"), rs.getInt("type"), rs.getLong("case_count")), caseId);
    }

    @Override
    public Map<Long, List<TagInfo>> tagsByCases(long projectId, List<Long> caseIds) {
        Map<Long, List<TagInfo>> result = new LinkedHashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return result;
        }
        jdbcTemplate.query("""
                SELECT ct.case_id, td.id, td.project_id, td.name, td.type
                FROM tp_case_tag ct
                JOIN tp_tag_dict td ON ct.tag_id = td.id
                WHERE td.project_id = ? AND ct.case_id IN (%s)
                ORDER BY td.id
                """.formatted(placeholders(caseIds.size())),
                rs -> {
                    long caseId = rs.getLong("case_id");
                    result.computeIfAbsent(caseId, k -> new ArrayList<>()).add(new TagInfo(
                            rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
                            rs.getInt("type"), 0L));
                }, concat(List.of(projectId), caseIds).toArray());
        return result;
    }


    private TagInfo requireTag(long tagId) {
        List<TagInfo> found = jdbcTemplate.query("""
                SELECT td.id, td.project_id, td.name, td.type,
                       (SELECT count(*) FROM tp_case_tag ct WHERE ct.tag_id = td.id) AS case_count
                FROM tp_tag_dict td WHERE td.id = ?
                """, (rs, rowNum) -> new TagInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("name"), rs.getInt("type"), rs.getLong("case_count")), tagId);
        if (found.isEmpty()) {
            throw new ServiceException("标签不存在或已被合并删除，请刷新「用例标签」页后重新选择目标标签",
                BizErrorCode.NOT_FOUND);
        }
        return found.get(0);
    }

    private void checkNameUnique(long projectId, String name, long excludeTagId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_tag_dict WHERE project_id = ? AND name = ? AND id <> ?
                """, Long.class, projectId, name, excludeTagId);
        if (count != null && count > 0) {
            throw new ServiceException("标签「" + name + "」已存在，请在「新建标签」或「标签改名」弹窗改用其他名称，或点击「合并」并入已有标签",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    private void validateType(int type) {
        boolean poolType = type == TYPE_POOL;
        boolean generalType = type >= TYPE_MODULE && type <= TYPE_FREE;
        if (!poolType && !generalType) {
            throw new ServiceException("标签类型仅支持 1=模块 2=功能 3=优先级 4=自由标签 7=资源池，请在「新建标签」弹窗的「标签类型」下拉中选择",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    private List<Object> concat(List<Object> head, List<Long> tail) {
        List<Object> all = new ArrayList<>(head);
        all.addAll(tail);
        return all;
    }
}

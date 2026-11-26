package com.sixbean.testpike.core.requirement.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cases.domain.component.CaseStatus;
import com.sixbean.testpike.common.defect.DefectLabels;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.TraceChain;
import com.sixbean.testpike.core.requirement.api.dto.TraceNode;
import com.sixbean.testpike.core.requirement.api.dto.TraceNodeType;
import com.sixbean.testpike.core.requirement.domain.component.TraceLabels;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.requirement.domain.service.TraceChainService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;


@Service
public class TraceChainServiceImpl implements TraceChainService {

    
    private static final int IN_CHUNK = 500;

    private final JdbcTemplate jdbcTemplate;
    private final RequirementService requirementService;

    public TraceChainServiceImpl(JdbcTemplate jdbcTemplate, RequirementService requirementService) {
        this.jdbcTemplate = jdbcTemplate;
        this.requirementService = requirementService;
    }

    @Override
    public TraceChain traceChainOfRequirement(long requirementId) {
        RequirementInfo root = requirementService.get(requirementId);
        TraceNode rootNode = new TraceNode(TraceNodeType.REQUIREMENT, root.id(), root.projectId(),
                root.title(), root.status(), TraceLabels.requirementStatus(root.status()),
                requirementRoute(root.projectId(), root.title()), null, null, null, null, null);

        String aliveCasesSql = """
                SELECT cr.case_id FROM tp_case_requirement cr
                JOIN tp_test_case c ON c.id = cr.case_id AND c.deleted = 0
                WHERE cr.requirement_id = ?
                """;
        int caseTotal = countQuery(
                "SELECT count(*) FROM (" + aliveCasesSql + ") t", requirementId);
        int defectTotal = countQuery("""
                SELECT (SELECT count(*) FROM tp_defect d WHERE d.case_id IN (""" + aliveCasesSql + """
                       )) + (SELECT count(*) FROM tp_case_defect x WHERE x.case_id IN (""" + aliveCasesSql + """
                       ))
                """, requirementId, requirementId);

        List<CaseRow> cases = jdbcTemplate.query("""
                SELECT c.id, c.project_id, c.case_no, c.title, c.status
                FROM tp_case_requirement cr
                JOIN tp_test_case c ON c.id = cr.case_id AND c.deleted = 0
                WHERE cr.requirement_id = ?
                ORDER BY cr.created_at, cr.id
                LIMIT ?
                """, (rs, i) -> new CaseRow(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("case_no"), rs.getString("title"), rs.getInt("status")),
                requirementId, TraceChain.MAX_NODES + 1);

        List<TraceNode> nodes = assembleNodes(cases);
        return build(rootNode, nodes, caseTotal + defectTotal);
    }

    @Override
    public TraceChain traceChainOfCase(long caseId) {
        CaseRow caseInfo = caseRowOf(caseId);
        ExecRow lastExec = latestExecOf(caseId);
        TraceNode rootNode = new TraceNode(TraceNodeType.CASE, caseInfo.id(), caseInfo.projectId(),
                caseInfo.caseNo() + " " + caseInfo.title(), caseInfo.status(),
                caseStatusLabel(caseInfo.status()),
                "/cases/" + caseInfo.id(),
                lastExec == null ? null : lastExec.batchId(),
                lastExec == null ? null : lastExec.batchName(),
                lastExec == null ? null : lastExec.status(),
                lastExec == null ? null : lastExec.finishedAt(), null);

        List<RequirementInfo> requirements = requirementService.listByCaseId(caseId);
        List<TraceNode> nodes = new ArrayList<>();
        for (RequirementInfo req : requirements) {
            nodes.add(new TraceNode(TraceNodeType.REQUIREMENT, req.id(), req.projectId(),
                    req.title(), req.status(), TraceLabels.requirementStatus(req.status()),
                    requirementRoute(req.projectId(), req.title()), null, null, null, null, null));
        }
        nodes.addAll(defectNodes(List.of(caseInfo)));

        return build(rootNode, nodes, nodes.size());
    }


    
    private record CaseRow(long id, long projectId, String caseNo, String title, int status) {
    }

    
    private record ExecRow(long caseId, Long batchId, String batchName, int status, String finishedAt) {
    }

    
    private List<TraceNode> assembleNodes(List<CaseRow> cases) {
        Map<Long, ExecRow> execByCase = latestExecs(cases);
        Map<Long, List<TraceNode>> defectsByCase = defectNodesMap(cases);
        List<TraceNode> nodes = new ArrayList<>();
        for (CaseRow c : cases) {
            ExecRow exec = execByCase.get(c.id());
            nodes.add(new TraceNode(TraceNodeType.CASE, c.id(), c.projectId(),
                    c.caseNo() + " " + c.title(), c.status(), caseStatusLabel(c.status()),
                    "/cases/" + c.id(),
                    exec == null ? null : exec.batchId(), exec == null ? null : exec.batchName(),
                    exec == null ? null : exec.status(), exec == null ? null : exec.finishedAt(), null));
            nodes.addAll(defectsByCase.getOrDefault(c.id(), List.of()));
        }
        return nodes;
    }

    private TraceChain build(TraceNode root, List<TraceNode> nodes, int nonRootTotal) {
        boolean truncated = nodes.size() > TraceChain.MAX_NODES || nonRootTotal > TraceChain.MAX_NODES;
        List<TraceNode> capped = nodes.size() > TraceChain.MAX_NODES
                ? List.copyOf(nodes.subList(0, TraceChain.MAX_NODES)) : nodes;
        return new TraceChain(root, capped, nonRootTotal, truncated);
    }

    
    private Map<Long, ExecRow> latestExecs(List<CaseRow> cases) {
        Map<Long, ExecRow> result = new HashMap<>();
        for (List<CaseRow> chunk : chunks(cases)) {
            jdbcTemplate.query("""
                    SELECT DISTINCT ON (t.case_id)
                           t.case_id, b.id AS batch_id, b.name AS batch_name, t.status,
                           to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at
                    FROM tp_exec_task t
                    JOIN tp_exec_batch b ON b.id = t.batch_id
                    WHERE t.case_id IN (%s)
                    ORDER BY t.case_id, t.id DESC
                    """.formatted(placeholders(chunk)), (rs, i) -> {
                result.put(rs.getLong("case_id"), new ExecRow(rs.getLong("case_id"),
                        rs.getLong("batch_id"), rs.getString("batch_name"),
                        rs.getInt("status"), rs.getString("finished_at")));
                return null;
            }, ids(chunk));
        }
        return result;
    }

    
    private ExecRow latestExecOf(long caseId) {
        List<ExecRow> rows = jdbcTemplate.query("""
                SELECT t.id, b.id AS batch_id, b.name AS batch_name, t.status,
                       to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at
                FROM tp_exec_task t
                JOIN tp_exec_batch b ON b.id = t.batch_id
                WHERE t.case_id = ?
                ORDER BY t.id DESC LIMIT 1
                """, (rs, i) -> new ExecRow(caseId, rs.getLong("batch_id"),
                rs.getString("batch_name"), rs.getInt("status"), rs.getString("finished_at")), caseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    
    private Map<Long, List<TraceNode>> defectNodesMap(List<CaseRow> cases) {
        Map<Long, List<TraceNode>> result = new HashMap<>();
        for (List<CaseRow> chunk : chunks(cases)) {
            Object[] ids = ids(chunk);
            Map<Long, String> caseRouteByCase = new HashMap<>();
            for (CaseRow c : chunk) {
                caseRouteByCase.put(c.id(), "/cases/" + c.id());
            }
            jdbcTemplate.query("""
                    SELECT d.id, d.project_id, d.case_id, d.title, d.status, d.external_key
                    FROM tp_defect d WHERE d.case_id IN (%s) ORDER BY d.id
                    """.formatted(placeholders(chunk)), (rs, i) -> {
                long caseId = rs.getLong("case_id");
                String externalKey = rs.getString("external_key");
                String title = rs.getString("title");
                result.computeIfAbsent(caseId, k -> new ArrayList<>()).add(new TraceNode(
                        TraceNodeType.DEFECT, rs.getLong("id"), rs.getLong("project_id"),
                        externalKey != null && !externalKey.isBlank() ? externalKey : title,
                        rs.getInt("status"), DefectLabels.statusLabel(rs.getInt("status")),
                        defectRoute(rs.getLong("project_id"), title), null, null, null, null,
                        "library"));
                return null;
            }, ids);
            jdbcTemplate.query("""
                    SELECT x.id, x.case_id, c.project_id, x.defect_key, x.defect_status, x.status
                    FROM tp_case_defect x
                    JOIN tp_test_case c ON c.id = x.case_id
                    WHERE x.case_id IN (%s) ORDER BY x.id
                    """.formatted(placeholders(chunk)), (rs, i) -> {
                long caseId = rs.getLong("case_id");
                result.computeIfAbsent(caseId, k -> new ArrayList<>()).add(new TraceNode(
                        TraceNodeType.DEFECT, rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("defect_key"), rs.getInt("status"),
                        TraceLabels.caseDefectStatus(rs.getString("defect_status")),
                        caseRouteByCase.get(caseId), null, null, null, null, "external"));
                return null;
            }, ids);
        }
        return result;
    }

    private List<TraceNode> defectNodes(List<CaseRow> cases) {
        List<TraceNode> nodes = new ArrayList<>();
        defectNodesMap(cases).values().forEach(nodes::addAll);
        return nodes;
    }


    private static String requirementRoute(long projectId, String title) {
        return "/requirements?projectId=" + projectId + "&keyword=" + encode(title);
    }

    private static String defectRoute(long projectId, String label) {
        return "/defects?projectId=" + projectId + "&keyword=" + encode(label);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String caseStatusLabel(int status) {
        CaseStatus parsed = CaseStatus.of(status);
        return parsed == null ? "未知(" + status + ")" : parsed.label();
    }

    
    private CaseRow caseRowOf(long caseId) {
        return jdbcTemplate.query(
                "SELECT id, project_id, case_no, title, status FROM tp_test_case "
                        + "WHERE id = ? AND deleted = 0",
                (rs, i) -> new CaseRow(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("case_no"), rs.getString("title"), rs.getInt("status")), caseId)
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("用例不存在或已被删除，请刷新「用例管理」列表后重新选择用例", BizErrorCode.NOT_FOUND));
    }

    private int countQuery(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String placeholders(List<CaseRow> chunk) {
        return SqlPlaceholders.in(chunk.size());
    }

    private static Object[] ids(List<CaseRow> chunk) {
        return chunk.stream().map(CaseRow::id).toArray();
    }

    private static List<List<CaseRow>> chunks(List<CaseRow> cases) {
        List<List<CaseRow>> result = new ArrayList<>();
        for (int i = 0; i < cases.size(); i += IN_CHUNK) {
            result.add(cases.subList(i, Math.min(i + IN_CHUNK, cases.size())));
        }
        return result;
    }
}

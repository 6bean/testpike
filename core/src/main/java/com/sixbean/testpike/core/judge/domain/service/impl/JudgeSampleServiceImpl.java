package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.component.CaseStatus;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import com.sixbean.testpike.core.judge.domain.component.AdoptedCaseRaiseMapper;
import com.sixbean.testpike.core.judge.domain.component.BatchResultRaiseMapper;
import com.sixbean.testpike.core.judge.domain.component.JudgeJsonCodec;
import com.sixbean.testpike.core.judge.domain.component.TrainingSampleRaiseMapper;
import com.sixbean.testpike.core.judge.domain.service.JudgeSampleService;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.JudgeSampleRow;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.domain.component.TraceLabels;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.sample.api.dto.TrainingSampleItem;
import com.sixbean.testpike.core.sample.domain.service.TrainingSampleService;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;


@Service
public class JudgeSampleServiceImpl implements JudgeSampleService {

 
    private static final Set<String> SOURCES =
            Set.of("manual", "batch-export", "training-raise", "ai-generated");

 
    private static final int EXEC_FAILED = 4;
    private static final int EXEC_TIMEOUT = 5;
    /** Golden eligibility: tp_test_case.source 1 = AI generated. */
    private static final int SOURCE_AI = 1;
    /** Golden eligibility: review passed (single source = CaseStatus enum code). */
    private static final int STATUS_APPROVED = CaseStatus.APPROVED.code();

    private static final String SELECT_COLUMNS = """
            SELECT id, set_name, version, snapshot_digest, input, expected,
                   reference_docs::text AS reference_docs_json, source, sensitive,
                   created_by, created_at
            FROM tp_judge_sample
            """;

    private final NamedParameterJdbcTemplate named;
    private final JudgeJsonCodec codec;
    private final TrainingSampleRaiseMapper raiseMapper;
    private final TrainingSampleService trainingSampleService;
    private final BatchResultRaiseMapper batchRaiseMapper;
    private final ExecTaskService execTaskService;
    private final CaseService caseService;
    private final RequirementService requirementService;
    private final AdoptedCaseRaiseMapper adoptedRaiseMapper;

    public JudgeSampleServiceImpl(NamedParameterJdbcTemplate named, JudgeJsonCodec codec,
                                  TrainingSampleRaiseMapper raiseMapper,
                                  TrainingSampleService trainingSampleService,
                                  BatchResultRaiseMapper batchRaiseMapper,
                                  ExecTaskService execTaskService,
                                  CaseService caseService,
                                  RequirementService requirementService,
                                  AdoptedCaseRaiseMapper adoptedRaiseMapper) {
        this.named = named;
        this.codec = codec;
        this.raiseMapper = raiseMapper;
        this.trainingSampleService = trainingSampleService;
        this.batchRaiseMapper = batchRaiseMapper;
        this.execTaskService = execTaskService;
        this.caseService = caseService;
        this.requirementService = requirementService;
        this.adoptedRaiseMapper = adoptedRaiseMapper;
    }

    @Override
    @Transactional
    public EvalSetVersionView publish(String setName, List<SampleInput> samples, Long createdBy) {
        String setNameTrimmed = requireText(setName, "评测集名");
        if (samples == null || samples.isEmpty()) {
            throw new ValidationException("评测集「" + setNameTrimmed + "」样本不能为空——"
                    + "空集发布无意义（删空请停用该集而非发新版本）");
        }
        for (SampleInput s : samples) {
            requireText(s.input(), "样本输入（input）");
            if (s.source() == null || !SOURCES.contains(s.source())) {
                throw new ValidationException("样本来源非法（可选 manual 手工 / batch-export "
                        + "批次导出 / training-raise 训练样本提升 / ai-generated AI 造题，收到 "
                        + s.source() + "）");
            }
        }
        int next = nextVersion(setNameTrimmed);
        String digest = snapshotDigest(samples);
        for (SampleInput s : samples) {
            named.update("INSERT INTO tp_judge_sample "
                            + "(set_name, version, snapshot_digest, input, expected, "
                            + "reference_docs, source, sensitive, created_by) "
                            + "VALUES (:setName, :version, :digest, :input, :expected, "
                            + "CAST(:refs AS jsonb), :source, :sensitive, :createdBy)",
                    new MapSqlParameterSource()
                            .addValue("setName", setNameTrimmed)
                            .addValue("version", next)
                            .addValue("digest", digest)
                            .addValue("input", s.input())
                            .addValue("expected", s.expected())
                            .addValue("refs", codec.write(s.referenceDocs()))
                            .addValue("source", s.source())
                            .addValue("sensitive", s.sensitive() == null ? 0 : s.sensitive())
                            .addValue("createdBy", createdBy));
        }
        return new EvalSetVersionView(setNameTrimmed, next, digest, samples.size());
    }

    @Override
    public List<SampleView> listSamples(String setName, int version) {
        List<SampleView> rows = named.query(SELECT_COLUMNS
                        + " WHERE set_name = :setName AND version = :version ORDER BY id",
                new MapSqlParameterSource().addValue("setName", setName)
                        .addValue("version", version),
                new BeanPropertyRowMapper<>(JudgeSampleRow.class))
                .stream().map(this::toView).toList();
        if (rows.isEmpty()) {
            throw new ValidationException("评测集「" + setName + "」版本 " + version
                    + " 不存在或无样本（fail-closed：禁空列表假绿，被评目标派发前必校验版本在位）");
        }
        return rows;
    }

    @Override
    public List<EvalSetVersionView> listSetVersions() {
        return named.queryForList("""
                        SELECT set_name, version, max(snapshot_digest) AS snapshot_digest,
                               count(*) AS sample_count
                        FROM tp_judge_sample
                        GROUP BY set_name, version
                        ORDER BY set_name ASC, version DESC
                        """,
                new MapSqlParameterSource()).stream()
                .map(row -> new EvalSetVersionView((String) row.get("set_name"),
                        ((Number) row.get("version")).intValue(),
                        (String) row.get("snapshot_digest"),
                        ((Number) row.get("sample_count")).intValue()))
                .toList();
    }

    @Override
    @Transactional
    public EvalSetVersionView raiseFromTrainingSamples(String setName, List<Long> sampleIds,
                                                       Long createdBy) {
        if (sampleIds == null || sampleIds.isEmpty()) {
            throw new ValidationException("提升训练样本为空——请从训练样本台账勾选至少一条"
                    + "再发布（空提升不产生任何评测样本）");
        }
        List<TrainingSampleItem> rows = trainingSampleService.listByIds(sampleIds);
        Set<Long> found = rows.stream().map(TrainingSampleItem::id).collect(Collectors.toSet());
        List<Long> missing = sampleIds.stream().filter(id -> id != null && !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new ValidationException("训练样本不存在（id=" + missing
                    + "）——请刷新训练样本台账后重选（整请求拒绝，不落半截版本）");
        }
        List<Long> rejectCaseIds = rows.stream()
                .filter(r -> TrainingSampleRaiseMapper.TYPE_REJECT.equals(r.sampleType()))
                .map(TrainingSampleItem::caseId).filter(Objects::nonNull).distinct().toList();
        Map<Long, TrainingSampleItem> paired =
                trainingSampleService.latestDiffByCaseIds(rejectCaseIds);
        List<SampleInput> samples = rows.stream()
                .map(r -> raiseMapper.toSample(r, paired.get(r.caseId())))
                .toList();
        return publish(setName, samples, createdBy);
    }

    @Override
    @Transactional
    public EvalSetVersionView raiseFromBatchResults(String setName, List<Long> taskIds,
                                                     Long createdBy) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new ValidationException("导出执行结果为空——请从批次结果页勾选至少一条失败/超时"
                    + "任务再发布（空导出不产生任何评测样本）");
        }
        List<ExecTaskInfo> tasks = execTaskService.listByIds(taskIds);
        Set<Long> found = tasks.stream().map(ExecTaskInfo::id).collect(Collectors.toSet());
        List<Long> missing = taskIds.stream().filter(id -> id != null && !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new ValidationException("执行任务不存在（id=" + missing
                    + "）——请刷新批次结果页后重选（整请求拒绝，不落半截版本）");
        }
        List<ExecTaskInfo> notFail = tasks.stream()
                .filter(t -> t.status() != EXEC_FAILED && t.status() != EXEC_TIMEOUT).toList();
        if (!notFail.isEmpty()) {
            throw new ValidationException("仅失败/超时的执行任务可导出为评测样本（"
                    + notFail.stream().map(t -> "任务 " + t.id() + "（当前" + execStatusText(t) + "）")
                            .collect(Collectors.joining("；"))
                    + "）——跑通的用例请走手工录入，驳回意见面请走训练样本提升");
        }
        Map<Long, CaseInfo> caseById = caseService.listByIds(
                        tasks.stream().map(ExecTaskInfo::caseId).distinct().toList()).stream()
                .collect(Collectors.toMap(CaseInfo::id, c -> c));
        List<Long> lostCases = tasks.stream().map(ExecTaskInfo::caseId)
                .filter(id -> !caseById.containsKey(id)).distinct().toList();
        if (!lostCases.isEmpty()) {
            throw new ValidationException("任务关联用例不存在或已删除（caseId=" + lostCases
                    + "）——被评上下文缺失无法导出（整请求拒绝，不落半截版本）");
        }
        List<SampleInput> samples = tasks.stream()
                .map(t -> batchRaiseMapper.toSample(t, caseById.get(t.caseId())))
                .toList();
        return publish(setName, samples, createdBy);
    }

    /**
 * Golden-pack harvest entry . Eligibility triple =
 * AI generated (source=1) + excellent mark + review approved; any miss
 * refuses the WHOLE request naming each row (human-picked selection lands
 * as a new immutable golden-set version through the shared publish path).
 */
    @Override
    @Transactional
    public EvalSetVersionView raiseFromAdoptedCases(String setName, List<Long> caseIds,
                                                    Long createdBy) {
        if (caseIds == null || caseIds.isEmpty()) {
            throw new ValidationException("收采纳入用例为空——请从采纳台账/用例列表勾选至少一条"
                    + "「AI 生成且优秀已通过」的用例再收采（空收采不产生任何黄金样本）");
        }
        List<CaseInfo> cases = caseService.listByIds(caseIds);
        Set<Long> found = cases.stream().map(CaseInfo::id).collect(Collectors.toSet());
        List<Long> missing = caseIds.stream().filter(id -> id != null && !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new ValidationException("用例不存在或已删除（id=" + missing
                    + "）——请刷新台账后重选（整请求拒绝，不落半截黄金版本）");
        }
        // Eligibility triple named row by row: AI source / excellent mark /
        // review approved. The golden answer must be exactly the batch a human
        // judged good AND released — mixing manual or unapproved rows skews the
        // replay drift rate the promotion gate weighs.
        List<String> ineligible = cases.stream()
                .filter(c -> c.source() != SOURCE_AI || c.excellent() != 1
                        || c.status() != STATUS_APPROVED)
                .map(c -> "用例 " + c.caseNo() + "（" + eligibilityGapText(c) + "）")
                .collect(Collectors.toList());
        if (!ineligible.isEmpty()) {
            throw new ValidationException("仅「AI 生成＋优秀标记＋评审通过」的用例可收采为黄金样本（"
                    + String.join("；", ineligible)
                    + "）——不达标项请先到用例页补齐标记/评审，或改走手工录入评测集");
        }
        List<SampleInput> samples = new ArrayList<>(cases.size());
        for (CaseInfo c : cases) {
            List<RequirementInfo> reqs = requirementService.listByCaseId(c.id());
            if (reqs.isEmpty()) {
                throw new ValidationException("用例 " + c.caseNo()
                        + " 未关联任何需求——黄金样本的输入面=需求上下文，无锚不造题"
                        + "（整请求拒绝；请先在需求-用例关联面补齐后重选）");
            }
            samples.add(adoptedRaiseMapper.toSample(c, reqs));
        }
        return publish(setName, samples, createdBy);
    }

    /** Plain-language gap list for the refusal copy (each missing gate named). */
    private String eligibilityGapText(CaseInfo caseInfo) {
        List<String> gaps = new ArrayList<>();
        if (caseInfo.source() != SOURCE_AI) {
            gaps.add("非 AI 生成（source=" + caseInfo.source() + "）");
        }
        if (caseInfo.excellent() != 1) {
            gaps.add("无优秀标记");
        }
        if (caseInfo.status() != STATUS_APPROVED) {
            CaseStatus current = CaseStatus.of(caseInfo.status());
            gaps.add("评审未通过（当前" + (current == null ? "未知状态" : current.label()) + "）");
        }
        return String.join("、", gaps);
    }

    
    private String execStatusText(ExecTaskInfo task) {
        return TraceLabels.execStatus(task.status());
    }

    
    private String snapshotDigest(List<SampleInput> samples) {
        Set<String> canonical = new TreeSet<>();
        for (SampleInput s : samples) {
            StringBuilder row = new StringBuilder();
            appendField(row, s.input());
            appendField(row, s.expected());
            appendField(row, codec.write(s.referenceDocs()));
            appendField(row, s.source());
            appendField(row, String.valueOf(s.sensitive() == null ? 0 : s.sensitive()));
            canonical.add(row.toString());
        }
        return DigestHex.sha256(String.join("\n", canonical));
    }

    private void appendField(StringBuilder buf, String value) {
        String v = value == null ? "" : value;
        buf.append(v.length()).append(':').append(v).append('|');
    }

    private int nextVersion(String setName) {
        Integer max = named.queryForObject("SELECT coalesce(max(version), 0) "
                        + "FROM tp_judge_sample WHERE set_name = :setName",
                new MapSqlParameterSource("setName", setName), Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    private SampleView toView(JudgeSampleRow row) {
        return new SampleView(row.getId(), row.getSetName(), row.getVersion(),
                row.getSnapshotDigest(), row.getInput(), row.getExpected(),
                codec.readStringList(row.getReferenceDocsJson()), row.getSource(),
                row.getSensitive(), row.getCreatedBy(), ViewFormats.formatTs(row.getCreatedAt()));
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + "不能为空");
        }
        return value.trim();
    }
}

package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.judge.api.dto.JudgeRunPageView;
import com.sixbean.testpike.core.judge.api.dto.RunCreateInput;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import com.sixbean.testpike.core.judge.domain.component.JudgeJsonCodec;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRubricService;
import com.sixbean.testpike.core.judge.domain.service.JudgeSampleService;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.JudgeRunRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class JudgeRunServiceImpl implements JudgeRunService {

 
    private static final Set<String> TARGET_TYPES = Set.of("pipeline", "external");

    static final int STATUS_PENDING = 0;
    static final int STATUS_RUNNING = 1;
    static final int STATUS_DONE = 2;
    static final int STATUS_FAILED = 3;

    private static final String SELECT_COLUMNS = """
            SELECT id, rubric_name, rubric_domain, rubric_version,
                   rubric_snapshot::text AS rubric_snapshot_json,
                   evalset_name, evalset_version, evalset_digest,
                   target_type, target_ref, llm_config_id, status, created_by,
                   created_at, finished_at
            FROM tp_judge_run
            """;

    private final NamedParameterJdbcTemplate named;
    private final JudgeJsonCodec codec;
    private final JudgeRubricService rubricService;
    private final JudgeSampleService sampleService;

    public JudgeRunServiceImpl(NamedParameterJdbcTemplate named, JudgeJsonCodec codec,
                               JudgeRubricService rubricService, JudgeSampleService sampleService) {
        this.named = named;
        this.codec = codec;
        this.rubricService = rubricService;
        this.sampleService = sampleService;
    }

    @Override
    @Transactional
    public RunView createRun(RunCreateInput input) {
        if (input.rubricId() == null) {
            throw new ValidationException("必须指定评分标准版本（rubricId 不能为空）");
        }
        RubricView rubric = rubricService.detail(input.rubricId());
        if (rubric == null) {
            throw new ValidationException("评分标准版本不存在（id=" + input.rubricId()
                    + "）——请先创建或选择有效版本");
        }
        if (input.targetType() == null || !TARGET_TYPES.contains(input.targetType())) {
            throw new ValidationException("被评目标类型非法（可选 pipeline 自家生成配置 / "
                    + "external 被测 AI 系统，收到 " + input.targetType() + "）");
        }
        if (input.targetRef() == null || input.targetRef().isBlank()) {
            throw new ValidationException("被评目标引用不能为空（生成配置 ID 或外部系统标识）");
        }

        String setName = requireText(input.evalsetName(), "评测集名");
        if (input.evalsetVersion() == null) {
            throw new ValidationException("必须指定评测集版本号（evalsetVersion 不能为空——"
                    + "Run 绑版本才能永久归因「模型变好还是题变水」）");
        }
        List<SampleView> samples = sampleService.listSamples(setName, input.evalsetVersion());
        String digest = samples.get(0).snapshotDigest();
        if (input.evalsetDigest() != null && !input.evalsetDigest().isBlank()
                && !input.evalsetDigest().equals(digest)) {
            throw new ValidationException("评测集快照锚不符：期望 " + input.evalsetDigest()
                    + "，库中版本实际为 " + digest
                    + "——该版本内容已被替换，请核对后按新版本重开评测（归因红线）");
        }

        List<Long> ids = named.queryForList("INSERT INTO tp_judge_run "
                        + "(rubric_name, rubric_domain, rubric_version, rubric_snapshot, "
                        + "evalset_name, evalset_version, evalset_digest, target_type, "
                        + "target_ref, llm_config_id, status, created_by) "
                        + "VALUES (:rubricName, :rubricDomain, :rubricVersion, "
                        + "CAST(:snapshot AS jsonb), :evalsetName, :evalsetVersion, :digest, "
                        + ":targetType, :targetRef, :llmConfigId, " + STATUS_PENDING
                        + ", :createdBy) RETURNING id",
                new MapSqlParameterSource()
                        .addValue("rubricName", rubric.name())
                        .addValue("rubricDomain", rubric.domain())
                        .addValue("rubricVersion", rubric.version())
                        .addValue("snapshot", codec.write(snapshotOf(rubric)))
                        .addValue("evalsetName", samples.get(0).setName())
                        .addValue("evalsetVersion", samples.get(0).version())
                        .addValue("digest", digest)
                        .addValue("targetType", input.targetType())
                        .addValue("targetRef", input.targetRef().trim())
                        .addValue("llmConfigId", input.llmConfigId())
                        .addValue("createdBy", input.createdBy()), Long.class);
        return toView(queryOne(ids.get(0)));
    }

    @Override
    public RunView detail(Long id) {
        JudgeRunRow row = queryOne(id);
        return row == null ? null : toView(row);
    }

    /** Page size ceiling for the run-history list (TREND_LIMIT_MAX precedent — no unbounded scan). */
    static final int RUN_LIST_SIZE_MAX = 200;

    @Override
    public JudgeRunPageView listRuns(String evalsetName, Integer status, int page, int size) {
        if (page < 1) {
            throw new ValidationException("页码非法（page=" + page + "，页码从 1 起）");
        }
        if (size < 1 || size > RUN_LIST_SIZE_MAX) {
            throw new ValidationException("每页条数非法（size=" + size + "，允许 1~"
                    + RUN_LIST_SIZE_MAX + "）——评测运行列表不做无界扫描");
        }
        if (status != null && (status < STATUS_PENDING || status > STATUS_FAILED)) {
            throw new ValidationException("状态筛选非法（status=" + status
                    + "，允许 0 待跑/1 跑批中/2 完成/3 失败）");
        }
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) (page - 1) * size);
        if (evalsetName != null && !evalsetName.isBlank()) {
            where.append(" AND evalset_name = :setName");
            params.addValue("setName", evalsetName.trim());
        }
        if (status != null) {
            where.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long total = named.queryForObject("SELECT count(*) FROM tp_judge_run" + where,
                params, Long.class);
        List<JudgeRunRow> rows = named.query(SELECT_COLUMNS + where
                        + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, new BeanPropertyRowMapper<>(JudgeRunRow.class));
        return new JudgeRunPageView(page, size, total == null ? 0 : total,
                rows.stream().map(this::toView).toList());
    }

    @Override
    @Transactional
    public void start(Long id) {
        int updated = named.update("UPDATE tp_judge_run SET status = " + STATUS_RUNNING
                + " WHERE id = :id AND status = " + STATUS_PENDING,
                new MapSqlParameterSource("id", id));
        if (updated == 0) {
            rejectTransition(id, "开始跑批（仅待跑态可开始）");
        }
    }

    @Override
    @Transactional
    public void finish(Long id, boolean success) {
        int terminal = success ? STATUS_DONE : STATUS_FAILED;
        int updated = named.update("UPDATE tp_judge_run SET status = :terminal, "
                        + "finished_at = now() WHERE id = :id AND status = " + STATUS_RUNNING,
                new MapSqlParameterSource().addValue("id", id).addValue("terminal", terminal));
        if (updated == 0) {
            rejectTransition(id, "结束评测（仅跑批中可结束，终态不可逆）");
        }
    }

    
    private Map<String, Object> snapshotOf(RubricView rubric) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", rubric.name());
        snap.put("domain", rubric.domain());
        snap.put("version", rubric.version());
        snap.put("dimensions", rubric.dimensions());
        snap.put("rules", rubric.rules());
        snap.put("passScore", rubric.passScore());
        snap.put("wRule", rubric.wRule());
        snap.put("wLlm", rubric.wLlm());
        return snap;
    }

    private void rejectTransition(Long id, String action) {
        JudgeRunRow row = queryOne(id);
        if (row == null) {
            throw new ValidationException("评测运行不存在（id=" + id + "）");
        }
        throw new ValidationException("评测运行当前状态（" + statusName(row.getStatus())
                + "）不允许执行「" + action + "」");
    }

    private String statusName(Integer status) {
        return switch (status == null ? -1 : status) {
            case STATUS_PENDING -> "待跑";
            case STATUS_RUNNING -> "跑批中";
            case STATUS_DONE -> "已完成";
            case STATUS_FAILED -> "已失败";
            default -> "未知";
        };
    }

    private JudgeRunRow queryOne(Long id) {
        List<JudgeRunRow> rows = named.query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(JudgeRunRow.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + "不能为空");
        }
        return value.trim();
    }

    private RunView toView(JudgeRunRow row) {
        return new RunView(row.getId(), row.getRubricName(), row.getRubricDomain(),
                row.getRubricVersion(), codec.readObjectMap(row.getRubricSnapshotJson()),
                row.getEvalsetName(), row.getEvalsetVersion(), row.getEvalsetDigest(),
                row.getTargetType(), row.getTargetRef(), row.getLlmConfigId(),
                row.getStatus(), row.getCreatedBy(), ViewFormats.formatTs(row.getCreatedAt()),
                ViewFormats.formatTs(row.getFinishedAt()));
    }
}

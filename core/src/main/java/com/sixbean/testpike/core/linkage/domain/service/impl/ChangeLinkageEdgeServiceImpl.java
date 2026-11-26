package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInfo;
import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInput;
import com.sixbean.testpike.core.linkage.domain.component.ChangeLinkageRowMapper;
import com.sixbean.testpike.core.linkage.domain.service.ChangeLinkageEdgeService;
import com.sixbean.testpike.core.linkage.infrastructure.dataobject.ChangeLinkageRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Change-linkage edge registry implementation (NamedParameterJdbcTemplate +
 * ChangeLinkageRow via the domain's single ChangeLinkageRowMapper).
 * <p>Validation mirrors the database CHECK constraints of tp_change_linkage so a bad
 * edge is refused with a readable reason before insert: a detector-less
 * non-exempt edge would make the bus silently skip downstream objects, and an
 * exemption without a written reason could never be audited. The exemption
 * vocabulary is closed here too: exempt rows carry detector kind 3 and the
 * {@code exempt} disposition, non-exempt rows carry a real detector and a real
 * disposition — mixed shapes would split the orphan-edge gate's reading of the
 * ledger.
 * <p>Registration is an idempotent upsert by edge_key: an identical replay
 * writes nothing (row id and updated_at stay frozen), a changed definition
 * updates in place. Disabling keeps the row for audit (enabled=0) instead of
 * deleting history.
 */
@Service
public class ChangeLinkageEdgeServiceImpl implements ChangeLinkageEdgeService {

    /** Closed vocabulary of disposition shapes (mirrors ck_change_linkage_disposition). */
    private static final Set<String> DISPOSITIONS =
            Set.of("mark_only", "grouped_draft", "disclose_only", "notify", "exempt");
    private static final String DISPOSITION_EXEMPT = "exempt";

    private static final String SELECT_COLUMNS = """
            SELECT id, edge_key, upstream_object_type, downstream_object_type, detector_kind,
                   detector_ref, trigger_field_predicate, disposition_strategy, risk_level,
                   exempt, exempt_reason, enabled, edge_note, created_at, updated_at
            FROM tp_change_linkage
            """;

    private final NamedParameterJdbcTemplate named;

    public ChangeLinkageEdgeServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    @Transactional
    public ChangeLinkageEdgeInfo register(ChangeLinkageEdgeInput input) {
        validate(input);
        ChangeLinkageRow existing = selectByEdgeKey(input.edgeKey());
        if (existing == null) {
            named.update("""
                    INSERT INTO tp_change_linkage (edge_key, upstream_object_type,
                            downstream_object_type, detector_kind, detector_ref,
                            trigger_field_predicate, disposition_strategy, risk_level,
                            exempt, exempt_reason, enabled, edge_note)
                    VALUES (:edgeKey, :upstream, :downstream, :detectorKind, :detectorRef,
                            :predicate, :disposition, :riskLevel, :exempt, :exemptReason,
                            1, :edgeNote)
                    """, params(input));
        } else if (!sameDefinition(existing, input)) {
            named.update("""
                    UPDATE tp_change_linkage
                       SET upstream_object_type = :upstream,
                           downstream_object_type = :downstream,
                           detector_kind = :detectorKind,
                           detector_ref = :detectorRef,
                           trigger_field_predicate = :predicate,
                           disposition_strategy = :disposition,
                           risk_level = :riskLevel,
                           exempt = :exempt,
                           exempt_reason = :exemptReason,
                           edge_note = :edgeNote,
                           updated_at = now()
                     WHERE edge_key = :edgeKey
                    """, params(input));
        }
        // Identical replay: no write at all — a replay is not a change fact.
        return findByEdgeKey(input.edgeKey());
    }

    @Override
    public List<ChangeLinkageEdgeInfo> findActiveEdges(String upstreamObjectType) {
        return named.query(SELECT_COLUMNS
                        + " WHERE upstream_object_type = :upstream AND enabled = 1 AND exempt = 0"
                        + " ORDER BY id",
                new MapSqlParameterSource("upstream", upstreamObjectType),
                ChangeLinkageRowMapper.INSTANCE)
                .stream().map(ChangeLinkageRow::toInfo).toList();
    }

    @Override
    public ChangeLinkageEdgeInfo findByEdgeKey(String edgeKey) {
        ChangeLinkageRow row = selectByEdgeKey(edgeKey);
        if (row == null) {
            throw new ServiceException("联动边 " + edgeKey
                    + " 不存在或已被清理，该关联的下游影响不再出现在「待处置的未提交改动」面板；"
                    + "请打开对应上游对象重新保存一次，联动影响会自动重建；仍失败请联系管理员核对联动配置",
                    BizErrorCode.NOT_FOUND);
        }
        return row.toInfo();
    }

    @Override
    @Transactional
    public ChangeLinkageEdgeInfo disable(String edgeKey) {
        int updated = named.update(
                "UPDATE tp_change_linkage SET enabled = 0, updated_at = now() WHERE edge_key = :k",
                new MapSqlParameterSource("k", edgeKey));
        if (updated == 0) {
            throw new ServiceException("停用失败：联动边 " + edgeKey
                    + " 不存在，该关联此前已被清理；请打开「待处置的未提交改动」面板确认，"
                    + "其中已无该条目即表示已生效，无需重复操作",
                    BizErrorCode.NOT_FOUND);
        }
        return findByEdgeKey(edgeKey);
    }

    private ChangeLinkageRow selectByEdgeKey(String edgeKey) {
        List<ChangeLinkageRow> rows = named.query(SELECT_COLUMNS + " WHERE edge_key = :k",
                new MapSqlParameterSource("k", edgeKey), ChangeLinkageRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MapSqlParameterSource params(ChangeLinkageEdgeInput input) {
        return new MapSqlParameterSource()
                .addValue("edgeKey", input.edgeKey())
                .addValue("upstream", input.upstreamObjectType())
                .addValue("downstream", input.downstreamObjectType())
                .addValue("detectorKind", input.detectorKind())
                .addValue("detectorRef", trimToNull(input.detectorRef()))
                .addValue("predicate", trimToNull(input.triggerFieldPredicate()))
                .addValue("disposition", input.dispositionStrategy())
                .addValue("riskLevel", input.riskLevel())
                .addValue("exempt", Integer.valueOf(1).equals(input.exempt()) ? 1 : 0)
                .addValue("exemptReason", trimToNull(input.exemptReason()))
                .addValue("edgeNote", trimToNull(input.edgeNote()));
    }

    private boolean sameDefinition(ChangeLinkageRow row, ChangeLinkageEdgeInput input) {
        ChangeLinkageEdgeInput stored = row.definition();
        return Objects.equals(stored.upstreamObjectType(), input.upstreamObjectType())
                && Objects.equals(stored.downstreamObjectType(), input.downstreamObjectType())
                && Objects.equals(stored.detectorKind(), input.detectorKind())
                && Objects.equals(stored.detectorRef(), trimToNull(input.detectorRef()))
                && Objects.equals(stored.triggerFieldPredicate(),
                        trimToNull(input.triggerFieldPredicate()))
                && Objects.equals(stored.dispositionStrategy(), input.dispositionStrategy())
                && Objects.equals(stored.riskLevel(), input.riskLevel())
                && Objects.equals(stored.exempt(), Integer.valueOf(1).equals(input.exempt()) ? 1 : 0)
                && Objects.equals(stored.exemptReason(), trimToNull(input.exemptReason()))
                && Objects.equals(stored.edgeNote(), trimToNull(input.edgeNote()));
    }

    private void validate(ChangeLinkageEdgeInput input) {
        if (input == null) {
            throw reject("联动边定义不能为空");
        }
        requireText(input.edgeKey(), 120, "边标识不能为空（120 字符内，如 case->plan_case）");
        requireText(input.upstreamObjectType(), 64, "上游对象类型不能为空（64 字符内，如 case）");
        requireText(input.downstreamObjectType(), 64, "下游对象类型不能为空（64 字符内，如 plan_case）");
        if (input.detectorKind() == null || input.detectorKind() < 1 || input.detectorKind() > 3) {
            throw reject("检测器类型只支持 1=Spring Bean 检测件 / 2=SQL 谓词 / 3=无检测器（豁免边）");
        }
        if (input.riskLevel() == null || input.riskLevel() < 1 || input.riskLevel() > 3) {
            throw reject("风险级别只支持 1/2/3：1=可逆标记态可自动，2=改内容需成组卡确认，3=仅披露");
        }
        if (!DISPOSITIONS.contains(input.dispositionStrategy())) {
            throw reject("处置方式只支持 mark_only / grouped_draft / disclose_only / notify / exempt");
        }
        boolean exemptRow = Integer.valueOf(1).equals(input.exempt());
        if (input.exempt() != null && !exemptRow && input.exempt() != 0) {
            throw reject("豁免标记只支持 0 或 1");
        }
        if (exemptRow) {
            requireText(input.exemptReason(), 500, "豁免边必须写明豁免理由，说明该上下游关系为何不传播");
            if (!DISPOSITION_EXEMPT.equals(input.dispositionStrategy())
                    || input.detectorKind() != 3) {
                throw reject("豁免边的检测器类型必须为 3（无检测器）且处置方式必须为 exempt");
            }
        } else {
            requireText(input.detectorRef(), 200,
                    "非豁免边必须登记检测器引用（Bean 名或 SQL 谓词），否则变更联动无法定位下游受影响对象");
            if (input.detectorKind() == 3 || DISPOSITION_EXEMPT.equals(input.dispositionStrategy())) {
                throw reject("非豁免边必须携带真实检测器（类型 1 或 2）和真实处置方式");
            }
        }
        maxLength(input.triggerFieldPredicate(), 500, "触发字段谓词最长 500 字符");
        maxLength(input.edgeNote(), 500, "边说明最长 500 字符");
    }

    private void requireText(String value, int max, String message) {
        if (value == null || value.isBlank()) {
            throw reject(message);
        }
        maxLength(value, max, message);
    }

    private void maxLength(String value, int max, String message) {
        if (value != null && value.length() > max) {
            throw reject(message);
        }
    }

    private ServiceException reject(String message) {
        return new ServiceException(message, BizErrorCode.VALIDATION_ERROR);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

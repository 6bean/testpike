package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.SystemDraftMintInput;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalRecord;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalWrite;
import com.sixbean.testpike.core.linkage.domain.component.LinkageImpactRowMapper;
import com.sixbean.testpike.core.linkage.domain.component.LinkageProposalRowMapper;
import com.sixbean.testpike.core.linkage.domain.service.LinkageProposalService;
import com.sixbean.testpike.core.linkage.infrastructure.dataobject.LinkageProposalRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Proposal snapshot store implementation (NamedParameterJdbcTemplate + the
 * domain's single LinkageProposalRowMapper).
 * <p>Write discipline mirrors the impact ledger's idempotency shape: rows are
 * keyed by impact_id, an identical replay writes nothing (no updated_at jump,
 * no phantom "changed" fact — same posture as the empty-save gate), a newer
 * base version re-arms the pending snapshot, and a consumed snapshot is
 * frozen because it records what the human already accepted. A rejected run
 * is journaled with its plain reason: an unusable draft that vanishes would
 * leave the pending impact looking ignorable forever.
 */
@Service
public class LinkageProposalServiceImpl implements LinkageProposalService {

    /** Snapshot states (mirror ck_linkage_proposal_status). */
    static final int STATUS_PENDING = 1;
    static final int STATUS_CONSUMED = 2;
    static final int STATUS_REJECTED = 3;

    private static final String SELECT_COLUMNS = """
            SELECT id, impact_id, downstream_object_type, downstream_object_id,
                   proposal_json, schema_version, model_name, base_script_version,
                   status, reject_reason, created_at, updated_at
            FROM tp_linkage_proposal
            """;

    private static final Logger log = LoggerFactory.getLogger(LinkageProposalServiceImpl.class);

    /** Ledger vocabulary (V515): only this disposition strategy gets a minted card. */
    private static final String STRATEGY_GROUPED_DRAFT = "grouped_draft";
    /** Impact ledger states (mirror tp_linkage_impact.status comments). */
    private static final int IMPACT_STALE_MARK = 1;
    private static final int IMPACT_DRAFT_PENDING = 2;

    private final NamedParameterJdbcTemplate named;
    private final SystemDraftService systemDraftService;

    public LinkageProposalServiceImpl(NamedParameterJdbcTemplate named,
                                      SystemDraftService systemDraftService) {
        this.named = named;
        this.systemDraftService = systemDraftService;
    }

    @Override
    @Transactional
    public LinkageProposalWrite record(LinkageProposalInput input) {
        if (input == null || input.impactId() == null) {
            throw new ServiceException("提案快照必须指明所属的联动影响行（字段 impactId）；"
                            + "请先在「待处置的未提交改动」面板或待处置影响查询中取得影响行编号，再随提案一起提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ((input.proposalJson() == null || input.proposalJson().isBlank())
                && (input.rejectReason() == null || input.rejectReason().isBlank())) {
            throw new ServiceException("「提案内容」与「不可用原因」至少要填写一个，两者皆空的快照不会保存为草案；"
                            + "请在「提案内容」写明建议改动，或在「不可用原因」说明为何暂不可用",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer impactExists = named.queryForObject(
                "SELECT count(*) FROM tp_linkage_impact WHERE id = :impactId",
                new MapSqlParameterSource("impactId", input.impactId()), Integer.class);
        if (impactExists == null || impactExists == 0) {
            throw new ServiceException("联动影响行 " + input.impactId()
                            + " 不存在，该影响可能已被处置或清理；请打开「待处置的未提交改动」面板核对最新编号后重新提交，"
                            + "仍失败请联系管理员核对影响记录",
                    BizErrorCode.NOT_FOUND);
        }

        LinkageProposalRow existing = selectByImpact(input.impactId());
        if (existing == null) {
            named.update("""
                    INSERT INTO tp_linkage_proposal
                        (impact_id, downstream_object_type, downstream_object_id,
                         proposal_json, schema_version, model_name, base_script_version,
                         status, reject_reason)
                    VALUES (:impactId, :downstreamType, :downstreamId,
                            CAST(:proposalJson AS jsonb), :schemaVersion, :modelName,
                            :baseVersion, :status, :rejectReason)
                    """, params(input, statusOf(input)));
            LinkageProposalRow inserted = Objects.requireNonNull(
                    selectByImpact(input.impactId()), "proposal row vanished right after insert");
            afterSnapshotWrite(inserted);
            return new LinkageProposalWrite(toRecord(inserted), true);
        }
        if (existing.status() == STATUS_CONSUMED) {
            // frozen: the human already accepted/acknowledged this impact
            return new LinkageProposalWrite(toRecord(existing), false);
        }
        if (sameSnapshot(existing, input)) {
            return new LinkageProposalWrite(toRecord(existing), false);
        }
        named.update("""
                UPDATE tp_linkage_proposal
                   SET downstream_object_type = :downstreamType,
                       downstream_object_id   = :downstreamId,
                       proposal_json = CAST(:proposalJson AS jsonb),
                       schema_version = :schemaVersion,
                       model_name     = :modelName,
                       base_script_version = :baseVersion,
                       status         = :status,
                       reject_reason  = :rejectReason,
                       updated_at     = now()
                 WHERE id = :id
                """, params(input, statusOf(input)).addValue("id", existing.id()));
        LinkageProposalRow refreshed = Objects.requireNonNull(
                selectByImpact(input.impactId()), "proposal row vanished right after update");
        afterSnapshotWrite(refreshed);
        return new LinkageProposalWrite(toRecord(refreshed), false);
    }

    @Override
    public LinkageProposalRecord get(long impactId) {
        LinkageProposalRow row = selectByImpact(impactId);
        return row == null ? null : toRecord(row);
    }

    @Override
    public LinkageImpactInfo impactOf(long impactId) {
        var rows = named.query(
                "SELECT " + LinkageImpactRowMapper.COLUMNS
                        + " FROM tp_linkage_impact WHERE id = :impactId",
                new MapSqlParameterSource("impactId", impactId),
                new LinkageImpactRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LinkageProposalRow selectByImpact(long impactId) {
        var rows = named.query(SELECT_COLUMNS + " WHERE impact_id = :impactId",
                new MapSqlParameterSource("impactId", impactId),
                LinkageProposalRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MapSqlParameterSource params(LinkageProposalInput input, int status) {
        return new MapSqlParameterSource()
                .addValue("impactId", input.impactId())
                .addValue("downstreamType", input.downstreamObjectType())
                .addValue("downstreamId", input.downstreamObjectId())
                .addValue("proposalJson", usableJson(input))
                .addValue("schemaVersion", input.schemaVersion())
                .addValue("modelName", input.modelName())
                .addValue("baseVersion", input.baseScriptVersion())
                .addValue("status", status)
                .addValue("rejectReason", input.rejectReason());
    }

    /** Status derives from content shape, never from the caller (one vocabulary). */
    private int statusOf(LinkageProposalInput input) {
        return usableJson(input) == null ? STATUS_REJECTED : STATUS_PENDING;
    }

    private String usableJson(LinkageProposalInput input) {
        return input.proposalJson() == null || input.proposalJson().isBlank()
                ? null : input.proposalJson();
    }

    /** Replay equality: same content AND same base version AND same status. */
    private boolean sameSnapshot(LinkageProposalRow existing, LinkageProposalInput input) {
        return Objects.equals(existing.proposalJson(), usableJson(input))
                && Objects.equals(existing.baseScriptVersion(), input.baseScriptVersion())
                && Objects.equals(existing.schemaVersion(), input.schemaVersion())
                && Objects.equals(existing.modelName(), input.modelName())
                && Objects.equals(existing.rejectReason(), input.rejectReason())
                && existing.status() == statusOf(input);
    }

    private LinkageProposalRecord toRecord(LinkageProposalRow row) {
        return new LinkageProposalRecord(
                row.id(), row.impactId(), row.downstreamObjectType(), row.downstreamObjectId(),
                row.proposalJson(), row.schemaVersion(), row.modelName(),
                row.baseScriptVersion(), row.status(), row.rejectReason(),
                toLocal(row.createdAt()), toLocal(row.updatedAt()));
    }

    private static LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : LocalDateTime.ofInstant(ts.toInstant(), ZoneId.systemDefault());
    }

    /**
 * The mint seam : a usable snapshot becomes the
 * one confirmable draft card behind the system-draft door, and the
 * impact row keeps its draft-pending state because the card genuinely
 * exists now. A rejected first run has no card to offer, so the row
 * honestly withdraws to the stale mark instead of pretending a draft is
 * pending forever. Which downstream rows mint at all is the ledger's
 * disposition strategy — never a type list duplicated in code.
 */
    private void afterSnapshotWrite(LinkageProposalRow row) {
        LinkageImpactInfo impact = impactOf(row.impactId());
        if (impact == null || !STRATEGY_GROUPED_DRAFT.equals(impact.dispositionStrategy())) {
            return;
        }
        if (row.status() == STATUS_PENDING) {
            try {
                mintCard(row);
            } catch (RuntimeException ex) {
                // an un-mintable anchor surfaces in the log ledger and retracts
                // the pending claim; a silently missing card is the real defect
                log.warn("linkage mint failed for impact {} ({}), withdrawing the "
                        + "draft-pending claim", row.impactId(), ex.getMessage());
                setImpactStatus(row.impactId(), IMPACT_STALE_MARK);
                return;
            }
            setImpactStatus(row.impactId(), IMPACT_DRAFT_PENDING);
        } else if (row.status() == STATUS_REJECTED && noCardKeyed(row.impactId())) {
            setImpactStatus(row.impactId(), IMPACT_STALE_MARK);
        }
    }

    /**
     * Resolve the entity the landing strategy aims at and hand the snapshot
     * to the single system-draft door. The script repair chain targets the
     * owning case; the contract-side members (AN ticket 4a) target their own
     * rows and anchor the project through their owning containers (mock space
     * / api-test collection), landing through the existing write strategies —
     * never a second door. An armed impact whose anchor row no longer resolves
     * is refused loudly so the caller withdraws the draft-pending claim
     * instead of showing an empty card.
     */
    private void mintCard(LinkageProposalRow row) {
        switch (row.downstreamObjectType()) {
            case "script" -> mintScriptRepairCard(row);
            case "mock_stub" -> mintContractStubCard(row);
            case "apitest_request" -> mintContractRequestCard(row);
            case "perf_approval" -> mintPerfReapprovalCard(row);
            default -> log.warn("linkage mint has no anchor resolver for impact {} downstream "
                    + "kind {} — nothing is minted", row.impactId(), row.downstreamObjectType());
        }
    }

    private void mintScriptRepairCard(LinkageProposalRow row) {
        List<Map<String, Object>> anchors = named.queryForList(
                "SELECT s.case_id AS case_id, c.project_id AS project_id FROM tp_script s"
                        + " JOIN tp_test_case c ON c.id = s.case_id WHERE s.id = :downstreamId",
                new MapSqlParameterSource("downstreamId", row.downstreamObjectId()));
        if (anchors.isEmpty() || anchors.get(0).get("project_id") == null) {
            log.warn("linkage mint skips impact {} — script {} no longer names an"
                    + " owning case with a project anchor", row.impactId(),
                    row.downstreamObjectId());
            return;
        }
        Map<String, Object> anchor = anchors.get(0);
        long targetCaseId = ((Number) anchor.get("case_id")).longValue();
        systemDraftService.mintLinkageDraft(new SystemDraftMintInput(
                ((Number) anchor.get("project_id")).longValue(),
                DraftObjectType.SCRIPT_REPAIR.wireName, targetCaseId,
                row.baseScriptVersion(), row.schemaVersion(), row.modelName(),
                row.proposalJson(),
                "联动修复草案：上游用例变更使该脚本与最新步骤不一致（用例 #" + targetCaseId
                        + "），接受后按草案落新版脚本",
                row.impactId()));
    }

    /**
     * Derived-stub card: the anchor walks the owning mock space for the
     * project, the optimistic anchor is the stub row's own version, and the
     * card lands through the existing MOCK_STUB_WRITE strategy (one door,
     * no second write path).
     */
    private void mintContractStubCard(LinkageProposalRow row) {
        List<Map<String, Object>> anchors = named.queryForList(
                "SELECT st.version AS stub_version, sp.project_id AS project_id"
                        + " FROM tp_mock_stub st JOIN tp_mock_space sp ON sp.id = st.space_id"
                        + " WHERE st.id = :downstreamId",
                new MapSqlParameterSource("downstreamId", row.downstreamObjectId()));
        if (anchors.isEmpty() || anchors.get(0).get("project_id") == null) {
            throw new IllegalStateException("mock stub " + row.downstreamObjectId()
                    + " no longer names an owning mock space with a project anchor");
        }
        Map<String, Object> anchor = anchors.get(0);
        systemDraftService.mintLinkageDraft(new SystemDraftMintInput(
                ((Number) anchor.get("project_id")).longValue(),
                DraftObjectType.MOCK_STUB_WRITE.wireName, row.downstreamObjectId(),
                ((Number) anchor.get("stub_version")).intValue(),
                row.schemaVersion(), row.modelName(), row.proposalJson(),
                "联动桩草案：上游接口契约新版本使该派生桩的匹配信息过期（桩 #"
                        + row.downstreamObjectId() + "），接受后按快照更新该桩",
                row.impactId()));
    }

    /**
     * Imported-request card: the anchor walks the owning api-test collection
     * for the project; the payload (collection defaults snapshot) is handed
     * verbatim to the existing APITEST_WRITE strategy.
     */
    private void mintContractRequestCard(LinkageProposalRow row) {
        List<Map<String, Object>> anchors = named.queryForList(
                "SELECT c.project_id AS project_id FROM tp_apitest_request r"
                        + " JOIN tp_apitest_collection c ON c.id = r.collection_id"
                        + " WHERE r.id = :downstreamId",
                new MapSqlParameterSource("downstreamId", row.downstreamObjectId()));
        if (anchors.isEmpty() || anchors.get(0).get("project_id") == null) {
            throw new IllegalStateException("apitest request " + row.downstreamObjectId()
                    + " no longer names an owning collection with a project anchor");
        }
        systemDraftService.mintLinkageDraft(new SystemDraftMintInput(
                ((Number) anchors.get(0).get("project_id")).longValue(),
                DraftObjectType.APITEST_WRITE.wireName, row.downstreamObjectId(),
                row.baseScriptVersion(), row.schemaVersion(), row.modelName(),
                row.proposalJson(),
                "联动接口集草案：上游接口契约新版本影响该引入请求所属接口集的默认配置"
                        + "（请求 #" + row.downstreamObjectId() + "），接受后按快照更新接口集默认头",
                row.impactId()));
    }

    /**
     * Re-approval card (AN ticket 4b): the anchor reads the affected approval
     * ticket's own project column — the ticket is the object the human
     * confirms an action on, so it targets the ticket row and the landing
     * strategy files a fresh request next to it (the history row is never
     * rewritten). There is no version ledger for this kind, hence
     * base_version stays null.
     */
    private void mintPerfReapprovalCard(LinkageProposalRow row) {
        List<Map<String, Object>> anchors = named.queryForList(
                "SELECT project_id FROM tp_perf_approval WHERE id = :downstreamId",
                new MapSqlParameterSource("downstreamId", row.downstreamObjectId()));
        if (anchors.isEmpty() || anchors.get(0).get("project_id") == null) {
            throw new IllegalStateException("perf approval " + row.downstreamObjectId()
                    + " no longer resolves to a ticket row with a project anchor");
        }
        systemDraftService.mintLinkageDraft(new SystemDraftMintInput(
                ((Number) anchors.get(0).get("project_id")).longValue(),
                DraftObjectType.PERF_APPROVAL.wireName, row.downstreamObjectId(),
                null, row.schemaVersion(), row.modelName(), row.proposalJson(),
                "联动重审草案：上游压测场景在上次审批后被修改，接受后按当前形状重新提交"
                        + "审批申请（审批单 #" + row.downstreamObjectId() + "）",
                row.impactId()));
    }

    private void setImpactStatus(long impactId, int status) {
        named.update("UPDATE tp_linkage_impact SET status = :status, annotated_at = now()"
                        + " WHERE id = :impactId AND status <> :status",
                new MapSqlParameterSource("impactId", impactId).addValue("status", status));
    }

    private boolean noCardKeyed(long impactId) {
        Integer cards = named.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_draft WHERE context_json->>'linkageImpactId' = :id",
                new MapSqlParameterSource("id", String.valueOf(impactId)), Integer.class);
        return cards == null || cards == 0;
    }
}

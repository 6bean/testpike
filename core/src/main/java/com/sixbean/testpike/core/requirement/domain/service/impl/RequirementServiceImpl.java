package com.sixbean.testpike.core.requirement.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementLinkResult;
import com.sixbean.testpike.core.requirement.api.dto.RequirementQuery;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementVersionInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementReviewAction;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.common.component.dedup.DedupCandidate;
import com.sixbean.testpike.core.common.component.dedup.DedupGate;
import com.sixbean.testpike.core.common.component.dedup.DedupVerdict;


import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.requirement.api.dto.RequirementSourceAnchor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;
import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.events.domain.service.RequirementEventPublisher;


@Service
public class RequirementServiceImpl implements RequirementService {

    /** Deep-pagination guard: max offset (same policy as CaseServiceImpl). */
    private static final int MAX_OFFSET = 100_000;
    private static final int MAX_SIZE = 200;

    /** tp_requirement_version.change_type (V329): 1=EDIT 2=STATUS 3=DELETE. */
    private static final int CHANGE_EDIT = 1;
    private static final int CHANGE_STATUS = 2;
    private static final int CHANGE_DELETE = 3;

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private static final String COLUMNS = """
            id, project_id, source, source_id, title, acceptance_criteria, status, chapter,
            created_by, created_at, updated_at, version, source_anchor::text AS source_anchor,
            fingerprint
            """;

    /** Server-side sort whitelist (requirement list; frontend column props map onto
     * these keys via SORT_KEY_MAP). Values are physical columns — never user input. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "title", "title",
            "source", "source",
            "source_id", "source_id",
            "status", "status",
            "updated_at", "updated_at");

    private final JdbcTemplate jdbcTemplate;
    private final CaseService caseService;
    private final DedupGate dedupGate;
    private final RequirementEventPublisher requirementEventPublisher;
    private final com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
            objectChangedEventPublisher;
    private final com.sixbean.testpike.core.config.domain.service.ConfigService configService;
    private final com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake behaviorOutcomeIntake;

    public RequirementServiceImpl(JdbcTemplate jdbcTemplate, CaseService caseService,
                                  DedupGate dedupGate,
                                  RequirementEventPublisher requirementEventPublisher,
                                  com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
                                          objectChangedEventPublisher,
                                  com.sixbean.testpike.core.config.domain.service.ConfigService configService,
                                  com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake behaviorOutcomeIntake) {
        this.jdbcTemplate = jdbcTemplate;
        this.caseService = caseService;
        this.dedupGate = dedupGate;
        this.requirementEventPublisher = requirementEventPublisher;
        this.objectChangedEventPublisher = objectChangedEventPublisher;
        this.configService = configService;
        this.behaviorOutcomeIntake = behaviorOutcomeIntake;
    }

    @Audit(objectType = "requirement", action = "create",
            objectId = "#result?.id() ?: #input.projectId()", after = "#result")
    @Transactional
    @Override
    public RequirementInfo create(Long operatorId, RequirementCreateInput input) {
        requireProject(input.projectId());
        String title = trimToNull(input.title());
        if (title == null || title.length() > 300) {
            throw new ServiceException("需求点标题不能为空且不超过 300 字，请在「新建需求点」弹窗重新填写标题",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int source = input.source() == null ? 3 : input.source();
        if (source < 1 || source > 3) {
            throw new ServiceException("需求点来源仅支持 1=Jira 2=文档提取 3=手工，请在「新建需求点」弹窗的「来源」改成 Jira、文档提取或手工三者之一",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Duplicate gate (design A7): three-tier verdict before insert, plus the
        // deterministic L1 re-check at write instant closing the card-to-commit
        // window. A reviewer-vouched force-new override skips both refusals and
        // leaves an override audit naming the blocked match.
        boolean forceNew = Boolean.TRUE.equals(input.dedupOverride());
        DedupVerdict verdict = dedupGate.evaluateRequirement(input.projectId(), title);
        if (verdict.blocksCreate()) {
            if (!forceNew) {
                throw duplicateRefusal(verdict.matched());
            }
            dedupGate.recordForceNewOverride(input.projectId(), title, verdict.matched(), operatorId);
        }
        if (!forceNew) {
            dedupGate.recheckRequirementL1(input.projectId(), title)
                    .ifPresent(RequirementServiceImpl::refuseExisting);
        }
        long id = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                INSERT INTO tp_requirement (project_id, source, source_id, title, acceptance_criteria,
                    status, chapter, created_by, source_anchor)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?::jsonb) RETURNING id
                """, Long.class, input.projectId(), source, trimToNull(input.sourceId()), title,
                trimToNull(input.acceptanceCriteria()), trimToNull(input.chapter()), operatorId,
                anchorJson(input.sourceAnchor())));
        RequirementInfo created = get(id);
        // Creating seeds v1 and its baseline snapshot (same posture as the case chain)
        // so every later diff has an anchor without special-casing the first version.
        writeSnapshot(id, created.version(), snapshotOf(created, 0), CHANGE_EDIT, operatorId, null);
        return created;
    }

    @Override
    public RequirementInfo get(long id) {
        List<RequirementInfo> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_requirement WHERE id = ? AND deleted = 0", this::mapRow, id);
        if (rows.isEmpty()) {
            throw new ServiceException("需求点 %d 不存在或已被删除，请在「需求点管理」列表刷新确认可选的需求点",
                    BizErrorCode.NOT_FOUND, id);
        }
        return rows.get(0);
    }

    @Override
    public PageResult<RequirementInfo> list(RequirementQuery query) {
        if (query.projectId() == null) {
            throw new ServiceException("projectId 必填（H1 隔离维度），请在顶栏选择项目后从「需求点管理」页发起查询",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int safePage = Math.max(query.page(), 1);
        int safeSize = Math.min(Math.max(query.size(), 1), MAX_SIZE);
        int offset = Math.min((safePage - 1) * safeSize, MAX_OFFSET);

        StringBuilder where = new StringBuilder(" WHERE project_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(query.projectId());
        if (query.status() != null) {
            where.append(" AND status = ?");
            args.add(query.status());
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (title ILIKE ? OR source_id ILIKE ?)");
            String kw = "%" + query.keyword().trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        // Structured deep-link handoff : rehydrate a known id set in one read.
        // Placeholders are generated from the list size only — no caller string ever
        // reaches the SQL text; an oversized list is a client mistake, refused honestly.
        if (query.ids() != null && !query.ids().isEmpty()) {
            if (query.ids().size() > MAX_SIZE) {
                throw new ServiceException("按 ID 清单查询需求点一次最多 " + MAX_SIZE + " 条（当前 "
                        + query.ids().size() + " 条），请分批带入，或在「需求点管理」用关键词分页查询",
                        BizErrorCode.VALIDATION_ERROR);
            }
            where.append(" AND id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(query.ids().size(), "?")))
                    .append(")");
            args.addAll(query.ids());
        }
        // Exact content-fingerprint lookup . The stored column is always the
        // uppercase XXXX-XXXX display form produced by requirement_fingerprint(), so the
        // input is normalized (trim + uppercase + dash insertion for the compact 8-hex form)
        // and compared exactly. An unknown or malformed value simply matches no row — an
        // honest zero result, never an exception and never a silently dropped filter.
        if (query.fingerprint() != null && !query.fingerprint().isBlank()) {
            where.append(" AND fingerprint = ?");
            args.add(normalizeFingerprint(query.fingerprint()));
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement" + where, Long.class, args.toArray());

        // Sort keys must resolve to a physical column through the shared whitelist —
        // user input never reaches the SQL string (SortColumnPolicy.require, same
        // posture as the plan/batch/suite domains; off-list key = fail-closed 400).
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "需求点");
        String order = sortColumn == null
                ? " ORDER BY id DESC"
                : " ORDER BY " + sortColumn
                        + ("asc".equalsIgnoreCase(query.sortDir()) ? " ASC" : " DESC") + ", id DESC";

        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(safeSize);
        listArgs.add(offset);
        List<RequirementInfo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_requirement" + where
                        + order + " LIMIT ? OFFSET ?", this::mapRow, listArgs.toArray());
        return new PageResult<>(list, total != null ? total : 0L);
    }

    /** Normalize fingerprint input to the stored XXXX-XXXX display form :
 * trim + uppercase, and the compact 8-hex form gets its dash inserted. Anything else
 * passes through unchanged — a value the column can never hold, so the exact match
 * honestly returns no rows instead of guessing what the user meant. */
    private static String normalizeFingerprint(String raw) {
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (v.matches("[0-9A-F]{8}")) {
            return v.substring(0, 4) + "-" + v.substring(4);
        }
        return v;
    }

    /**
     * Case-gap list (generation pipeline advice): NOT EXISTS over the active
     * coverage wording — the subquery deliberately mirrors
     * CoverageMatrixServiceImpl (a link counts only for a live, non-archived
     * case; retired requirements are out of scope). Single source of the
     * wording lives in that matrix component; this SQL stays annotation-
     * anchored to it instead of defining a second semantics (R40).
     */
    @Override
    public PageResult<RequirementInfo> listUncovered(long projectId, int page, int size) {
        if (!caseGapSuggestEnabled()) {
            throw new ServiceException("无用例需求点建议当前已关闭，可在「系统设置 > 参数配置」中打开 "
                    + RequirementService.KEY_CASE_GAP_SUGGEST + " 后即时生效",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int offset = Math.min((safePage - 1) * safeSize, MAX_OFFSET);
        Long total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_requirement r
                WHERE r.project_id = ? AND r.deleted = 0 AND r.status <> 3
                  AND NOT EXISTS (
                        SELECT 1 FROM tp_case_requirement cr
                        JOIN tp_test_case c ON c.id = cr.case_id
                             AND c.deleted = 0 AND c.status <> 9
                        WHERE cr.requirement_id = r.id)
                """, Long.class, projectId);
        List<RequirementInfo> list = jdbcTemplate.query("""
                SELECT r.id, r.project_id, r.source, r.source_id, r.title, r.acceptance_criteria,
                       r.status, r.chapter, r.created_by, r.created_at, r.updated_at, r.version,
                       r.source_anchor::text AS source_anchor, r.fingerprint
                FROM tp_requirement r
                WHERE r.project_id = ? AND r.deleted = 0 AND r.status <> 3
                  AND NOT EXISTS (
                        SELECT 1 FROM tp_case_requirement cr
                        JOIN tp_test_case c ON c.id = cr.case_id
                             AND c.deleted = 0 AND c.status <> 9
                        WHERE cr.requirement_id = r.id)
                ORDER BY r.id DESC LIMIT ? OFFSET ?
                """, this::mapRow, projectId, safeSize, offset);
        return new PageResult<>(list, total != null ? total : 0L);
    }

    /** Factory-on boolean read (same honest posture as the extraction preview):
     * anything but an explicit false/0 keeps the advice channel open. */
    private boolean caseGapSuggestEnabled() {
        String raw = configService.getValue(RequirementService.KEY_CASE_GAP_SUGGEST, "true");
        String v = raw == null ? "true" : raw.trim().toLowerCase();
        return !("false".equals(v) || "0".equals(v));
    }

    @Audit(objectType = "requirement", action = "update", objectId = "#id", after = "#result")
    @Transactional
    @Override
    public RequirementInfo update(Long operatorId, long id, RequirementUpdateInput input) {
        RequirementInfo current = get(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));

        Integer expectedVersion = input.expectedVersion();
        if (expectedVersion != null && !expectedVersion.equals(current.version())) {
            // 40901 names the current version AND the submitted baseline so the writer
            // can re-anchor without guessing (generic conflict text is not acceptable).
            throw new OptimisticLockingException("需求点已被他人修改（当前版本 v" + current.version()
                    + "，你提交的基线 v" + expectedVersion + "），请刷新后重试");
        }

        // Field-level merge: null leaves the column untouched; a supplied value equal to
        // the current one is not a change (empty-save idempotency posture, R28).
        String title = current.title();
        if (input.title() != null) {
            String trimmed = trimToNull(input.title());
            if (trimmed == null || trimmed.length() > 300) {
                throw new ServiceException("需求点标题不能为空且不超过 300 字，请在「编辑需求点」弹窗重新填写标题",
                        BizErrorCode.VALIDATION_ERROR);
            }
            title = trimmed;
        }
        Integer source = current.source();
        if (input.source() != null) {
            if (input.source() < 1 || input.source() > 3) {
                throw new ServiceException("需求点来源仅支持 1=Jira 2=文档提取 3=手工，请在「编辑需求点」弹窗的「来源」改成 Jira、文档提取或手工三者之一",
                        BizErrorCode.VALIDATION_ERROR);
            }
            source = input.source();
        }
        String sourceId = current.sourceId();
        if (input.sourceId() != null) {
            sourceId = trimToNull(input.sourceId());
        }
        String acceptanceCriteria = current.acceptanceCriteria();
        if (input.acceptanceCriteria() != null) {
            acceptanceCriteria = trimToNull(input.acceptanceCriteria());
        }
        String chapter = current.chapter();
        if (input.chapter() != null) {
            chapter = trimToNull(input.chapter());
        }
        Integer status = current.status();
        if (input.status() != null) {
            if (input.status() < 1 || input.status() > 3) {
                throw new ServiceException("需求点状态仅支持 1=活跃 2=已变更 3=已下线，请在「编辑需求点」弹窗的「状态」改成 活跃、已变更 或 已下线",
                        BizErrorCode.VALIDATION_ERROR);
            }
            status = input.status();
        }

        boolean contentChanged = !Objects.equals(title, current.title())
                || !Objects.equals(source, current.source())
                || !Objects.equals(sourceId, current.sourceId())
                || !Objects.equals(acceptanceCriteria, current.acceptanceCriteria())
                || !Objects.equals(chapter, current.chapter());
        boolean statusChanged = !Objects.equals(status, current.status());
        if (!contentChanged && !statusChanged) {
            return current; // idempotent replay: zero writes, no version bump, no snapshot
        }

        StringBuilder set = new StringBuilder(" updated_at = now()");
        List<Object> args = new ArrayList<>();
        if (input.title() != null) {
            set.append(", title = ?");
            args.add(title);
        }
        if (input.source() != null) {
            set.append(", source = ?");
            args.add(source);
        }
        if (input.sourceId() != null) {
            set.append(", source_id = ?");
            args.add(sourceId);
        }
        if (input.acceptanceCriteria() != null) {
            set.append(", acceptance_criteria = ?");
            args.add(acceptanceCriteria);
        }
        if (input.chapter() != null) {
            set.append(", chapter = ?");
            args.add(chapter);
        }
        if (input.status() != null) {
            set.append(", status = ?");
            args.add(status);
        }
        int casBaseline = current.version();
        args.add(id);
        args.add(casBaseline);
        // Compare-and-swap on the row version: concurrent submissions on the same
        // baseline can both pass the pre-check, but only one UPDATE matches
        // version = ? — the loser gets 40901 instead of silently clobbering.
        int rows = jdbcTemplate.update("UPDATE tp_requirement SET" + set
                + ", version = version + 1 WHERE id = ? AND version = ?", args.toArray());
        if (rows != 1) {
            RequirementInfo fresh = get(id);
            throw new OptimisticLockingException("需求点已被他人修改（当前版本 v" + fresh.version()
                    + "，你提交的基线 v" + casBaseline + "），请刷新后重试");
        }
        int changeType = contentChanged ? CHANGE_EDIT : CHANGE_STATUS;
        RequirementInfo updated = get(id);
        writeSnapshot(updated.id(), updated.version(), snapshotOf(updated, 0),
                changeType, operatorId, snapshotOf(current, 0));
        // Save-chain event (afterCommit discipline lives in the publisher): every
        // really-written requirement row announces requirement.changed carrying
        // the post-write version, the consumer idempotency key source.
        requirementEventPublisher.publish(new RequirementEventMessage(
                RequirementEventType.CHANGED.name(), updated.id(), updated.projectId(),
                operatorId, updated.version(), System.currentTimeMillis()));
        publishLinkageAnnouncement(updated.id(), updated.projectId(), operatorId,
                updated.version());
        return updated;
    }

    @Audit(objectType = "requirement", action = "review", objectId = "#id")
    @Transactional
    @Override
    public RequirementInfo submitReview(Long operatorId, long id, RequirementReviewAction action,
                                        String remark) {
        RequirementInfo current = get(id); // missing or soft-deleted rows never open this door
        if ((action == RequirementReviewAction.NEED_CHANGE || action == RequirementReviewAction.REJECT)
                && trimToNull(remark) == null) {
            throw new ServiceException("评审动作为需修改或拒绝时必须填写评审意见，请在评审时补充修改或拒绝的原因，示例：验收标准缺少边界条件说明",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Object[]> newest = jdbcTemplate.query(
                "SELECT action, applied_version FROM tp_requirement_review_log"
                        + " WHERE requirement_id = ? ORDER BY id DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)}, id);
        boolean alreadyPassedOnCurrentVersion = action == RequirementReviewAction.PASS
                && !newest.isEmpty()
                && "PASS".equals(newest.get(0)[0])
                && ((Integer) newest.get(0)[1]).intValue() == current.version().intValue();
        if (alreadyPassedOnCurrentVersion) {
            return current; // same-version PASS replay: zero writes, zero ledger churn
        }
        if (action == RequirementReviewAction.PASS && Integer.valueOf(2).equals(current.status())) {
            // Ride the human-facing update chain (compare-and-swap + snapshot +
            // changed event) so this door never grows a second write path.
            current = update(operatorId, id, new RequirementUpdateInput(
                    null, null, null, null, null, 1, null));
        }
        final Object appliedVersion = current.version(); // 'current' is reassigned above; lambda needs a final snapshot
        final Long reviewProjectId = current.projectId();
        org.springframework.jdbc.support.GeneratedKeyHolder reviewLogKey =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update((java.sql.Connection con) -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tp_requirement_review_log"
                            + " (requirement_id, user_id, action, remark, applied_version)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, id);
            ps.setObject(2, operatorId);
            ps.setString(3, action.name());
            ps.setString(4, trimToNull(remark));
            ps.setObject(5, appliedVersion);
            return ps;
        }, reviewLogKey);
        java.lang.Number requirementReviewLogId = reviewLogKey.getKey();
        behaviorOutcomeIntake.recordReviewVerdict(
                com.sixbean.testpike.core.behavior.domain.component.OutcomeChannel.REQUIREMENT_REVIEW,
                requirementReviewLogId == null ? null : requirementReviewLogId.longValue(),
                com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake
                        .reviewOutcome(action.name()),
                String.valueOf(id), reviewProjectId);
        if (action == RequirementReviewAction.PASS) {
            // The ingest door opens exactly here; consumers key idempotency on
            // kb-ingest:req:{id}:{version} carried by the event version.
            requirementEventPublisher.publish(new RequirementEventMessage(
                    RequirementEventType.REVIEW_PASSED.name(), id, current.projectId(),
                    operatorId, current.version(), System.currentTimeMillis()));
            publishLinkageAnnouncement(id, current.projectId(), operatorId,
                    current.version());
        }
        return current;
    }

    @Audit(objectType = "requirement", action = "delete", objectId = "#id")
    @Transactional
    @Override
    public long delete(Long operatorId, long id) {
        RequirementInfo current = get(id);
        Integer linked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id = ?", Integer.class, id);
        if (linked != null && linked > 0) {
            throw new ServiceException("该需求点已被用例关联，请在「需求点管理」该行打开「关联用例」面板取消关联，或在「编辑需求点」弹窗将状态改成已下线",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        // Soft delete (F3): the row stays so the version chain keeps resolving and
        // auditors can replay history; every reader filters deleted = 0. The flag
        // flip IS the audited change, so its snapshot (change_type=3) is written
        // unconditionally instead of being deduped against the previous content.
        int rows = jdbcTemplate.update("UPDATE tp_requirement SET deleted = 1, version = version + 1, "
                + "updated_at = now() WHERE id = ? AND deleted = 0", id);
        if (rows == 0) {
            throw new ServiceException("需求点 %d 不存在或已被删除，请在「需求点管理」列表刷新确认后再删除",
                    BizErrorCode.NOT_FOUND, id);
        }
        writeSnapshot(id, current.version() + 1, snapshotOf(current, 1),
                CHANGE_DELETE, operatorId, null);
        // Delete-chain event: announce requirement.changed carrying the post-bump
        // version (same value the delete snapshot pins) so the knowledge-base
        // ingest door retires the entry — same afterCommit discipline as the
        // save chain, rollback emits nothing.
        requirementEventPublisher.publish(new RequirementEventMessage(
                RequirementEventType.CHANGED.name(), id, current.projectId(),
                operatorId, current.version() + 1, System.currentTimeMillis()));
        publishLinkageAnnouncement(id, current.projectId(), operatorId,
                current.version() + 1);
        return id;
    }

    /**
     * Change-linkage bus announcement of a really-written requirement row —
     * the generic object-changed frame alongside the legacy requirement
     * family (same requirement.changed routing key, no second key invented;
     * same posture as the case save chain in CaseServiceImpl). Empty changed
     * fields mean conservative arming per the DTO contract, and the
     * afterCommit discipline lives in the publisher. Re-announcing one
     * version is harmless: the impact ledger is keyed by edge + row + version.
     */
    private void publishLinkageAnnouncement(long id, Long projectId, Long operatorId,
                                            int version) {
        objectChangedEventPublisher.publish(
                new com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage(
                        com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType
                                .REQUIREMENT.name(),
                        id, projectId, operatorId, version, List.of(),
                        System.currentTimeMillis()));
    }

    @Audit(objectType = "requirement", action = "link",
            objectId = "#requirementId", after = "#result")
    @Transactional
    @Override
    public RequirementLinkResult linkCases(Long operatorId, long requirementId, List<Long> caseIds) {
        RequirementInfo requirement = get(requirementId);
        if (caseIds == null || caseIds.isEmpty()) {
            throw new ServiceException("请选择要关联的用例，可在「需求点管理」该行打开「关联用例」面板从候选列表勾选后点击「关联所选」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<Long> unique = new HashSet<>(caseIds);
        for (Long caseId : unique) {
            List<Long> caseProjectRows = jdbcTemplate.query(
                    "SELECT project_id FROM tp_test_case WHERE id = ? AND deleted = 0",
                    (rs, rowNum) -> rs.getLong("project_id"), caseId);
            if (caseProjectRows.isEmpty()) {
                throw new ServiceException("用例 %s 不存在或已被删除，请在「用例管理」列表刷新后重新勾选要关联的用例",
                        BizErrorCode.NOT_FOUND, caseId);
            }
            if (!caseProjectRows.get(0).equals(requirement.projectId())) {
                throw new ServiceException("用例 %s 不属于该需求点所在项目，请在顶栏切换到该用例所属项目后到「用例管理」确认，再回「需求点管理」关联",
                        BizErrorCode.VALIDATION_ERROR, caseId);
            }
        }
        int linked = 0;
        for (Long caseId : unique) {
            Integer existed = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_case_requirement WHERE case_id = ? AND requirement_id = ?",
                    Integer.class, caseId, requirementId);
            if (existed == null || existed == 0) {
                jdbcTemplate.update("""
                        INSERT INTO tp_case_requirement (case_id, requirement_id, created_by)
                        VALUES (?, ?, ?)
                        """, caseId, requirementId, operatorId);
                linked++;
            }
        }
        return new RequirementLinkResult(requirementId, new ArrayList<>(unique), linked);
    }

    @Audit(objectType = "requirement", action = "unlink", objectId = "#requirementId")
    @Transactional
    @Override
    public long unlinkCase(Long operatorId, long requirementId, long caseId) {
        get(requirementId);
        jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE case_id = ? AND requirement_id = ?",
                caseId, requirementId);
        return requirementId;
    }

    @Override
    public List<RequirementInfo> listByCaseId(long caseId) {
        return jdbcTemplate.query("""
                SELECT r.id, r.project_id, r.source, r.source_id, r.title, r.acceptance_criteria,
                       r.status, r.chapter, r.created_by, r.created_at, r.updated_at, r.version,
                       r.source_anchor::text AS source_anchor, r.fingerprint
                FROM tp_case_requirement cr
                JOIN tp_requirement r ON r.id = cr.requirement_id AND r.deleted = 0
                WHERE cr.case_id = ?
                ORDER BY cr.created_at, cr.id
                """, this::mapRow, caseId);
    }

 
    @Override
    public List<CaseInfo> listCasesByRequirementId(long requirementId) {
        get(requirementId);
        List<Long> caseIds = jdbcTemplate.query(
                "SELECT case_id FROM tp_case_requirement WHERE requirement_id = ? ORDER BY created_at, id",
                (rs, rowNum) -> rs.getLong(1), requirementId);
        if (caseIds.isEmpty()) {
            return List.of();
        }
        Map<Long, CaseInfo> byId = caseService.listByIds(caseIds).stream()
                .collect(Collectors.toMap(CaseInfo::id, c -> c, (a, b) -> a, HashMap::new));
        return caseIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    
    @Override
    public List<Long> offlineCaseIds(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return List.of();
        }
        Object[] args = java.util.stream.Stream
                .concat(java.util.stream.Stream.of(projectId), caseIds.stream()).toArray();
        return jdbcTemplate.queryForList("""
                SELECT cr.case_id
                FROM tp_case_requirement cr
                JOIN tp_requirement r ON r.id = cr.requirement_id AND r.status = 3 AND r.deleted = 0
                JOIN tp_test_case c ON c.id = cr.case_id AND c.project_id = ? AND c.deleted = 0
                WHERE cr.case_id IN (%s)
                GROUP BY cr.case_id
                ORDER BY cr.case_id
                """.formatted(com.sixbean.testpike.core.common.domain.component.SqlPlaceholders
                        .in(caseIds.size())),
                Long.class, args);
    }

    /**
     * Full-row content projection stored as snapshot_json (V329: the version chain is
     * auditable without joining back to the mutable row). The deleted flag is part of
     * the projection so the delete marker always differs from the previous snapshot.
     */
    private Map<String, Object> snapshotOf(RequirementInfo info, int deletedFlag) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("projectId", info.projectId());
        snapshot.put("source", info.source());
        snapshot.put("sourceId", info.sourceId());
        snapshot.put("title", info.title());
        snapshot.put("acceptanceCriteria", info.acceptanceCriteria());
        snapshot.put("status", info.status());
        snapshot.put("chapter", info.chapter());
        snapshot.put("deleted", deletedFlag);
        return snapshot;
    }

    /**
     * Writes a version snapshot; uk(requirement_id, version) plus ON CONFLICT DO NOTHING
     * keep replays idempotent (mirrors the tp_case_version writeSnapshot single-source
     * posture). previous != null with identical content writes nothing - an edit that
     * changes nothing burns no version.
     */
    private void writeSnapshot(long requirementId, int version, Map<String, Object> snapshot,
                               int changeType, Long operatorId, Map<String, Object> previous) {
        if (previous != null && previous.equals(snapshot)) {
            return;
        }
        String json;
        try {
            json = SNAPSHOT_MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("需求点版本快照序列化失败 id=" + requirementId, e);
        }
        jdbcTemplate.update("""
                INSERT INTO tp_requirement_version (requirement_id, version, snapshot_json, change_type, operator_id)
                VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (requirement_id, version) DO NOTHING
                """, requirementId, version, json, changeType, operatorId);
    }

    /**
     * Version chain read surface (GET /internal/requirements/{id}/versions): newest first,
     * operator display name resolved, changed-field count baselined on the previous version.
     * Corrupted snapshots are marked, never 500 - same posture as the case version list.
     */
    @Override
    public List<RequirementVersionInfo> listVersions(long id) {
        get(id); // reuse the detail 40401 wording: unknown or soft-deleted rows stay invisible
        List<VersionRow> rows = jdbcTemplate.query("""
                SELECT v.id, v.version, v.change_type, v.operator_id, v.created_at,
                       v.snapshot_json::text AS snapshot,
                       COALESCE(NULLIF(u.display_name, ''), u.username) AS operator_name
                FROM tp_requirement_version v
                LEFT JOIN tp_user u ON u.id = v.operator_id
                WHERE v.requirement_id = ?
                ORDER BY v.version DESC
                """, (rs, n) -> new VersionRow(rs.getLong("id"), rs.getInt("version"),
                rs.getInt("change_type"), rs.getObject("operator_id", Long.class),
                rs.getObject("created_at", LocalDateTime.class), rs.getString("snapshot"),
                rs.getString("operator_name")), id);
        List<VersionRow> asc = new ArrayList<>(rows);
        asc.sort(Comparator.comparingInt(VersionRow::version));
        Map<Integer, RequirementVersionInfo> byVersion = new java.util.LinkedHashMap<>();
        Map<String, Object> baseline = null;
        for (VersionRow row : asc) {
            Map<String, Object> snapshot;
            try {
                snapshot = SNAPSHOT_MAPPER.readValue(row.snapshotJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                snapshot = null; // corrupt row: mark it and keep the rest of the chain readable
            }
            Integer changed = snapshot == null ? null
                    : baseline == null ? 0 : countChangedKeys(baseline, snapshot);
            if (snapshot != null) {
                baseline = snapshot;
            }
            byVersion.put(row.version(), new RequirementVersionInfo(row.id(), id, row.version(),
                    row.changeType(), row.operatorId(), row.operatorName(), row.createdAt(),
                    changed, snapshot == null));
        }
        return rows.stream().map(r -> byVersion.get(r.version())).toList();
    }

    /** Snapshot keys whose value moved between two adjacent versions (a missing side counts as moved). */
    private int countChangedKeys(Map<String, Object> previous, Map<String, Object> current) {
        Set<String> keys = new HashSet<>(previous.keySet());
        keys.addAll(current.keySet());
        int changed = 0;
        for (String key : keys) {
            if (!Objects.equals(previous.get(key), current.get(key))) {
                changed++;
            }
        }
        return changed;
    }

    /** A decoded version row as an internal data carrier for one listVersions query, not an API DTO. */
    private record VersionRow(long id, int version, int changeType, Long operatorId,
                              LocalDateTime createdAt, String snapshotJson, String operatorName) {
    }

    /** Gate refusal when the verdict names an existing row (single wording source). */
    private static ServiceException duplicateRefusal(DedupCandidate existing) {
        return new ServiceException(String.format(
                "已存在同名或高相似需求点（id=%d，标题《%s》），本次新建被重复检出拦截；"
                        + "请先更新既有条目，如确属不同需求请调整标题。"
                        + "该拦截可在「系统设置 → 系统配置」将 ai.dedup.enabled 设为关闭（修改即时生效）",
                existing.id(), existing.title()),
                BizErrorCode.VALIDATION_ERROR);
    }

    /** If the write-instant re-check found a homonym, refuse with the same wording. */
    private static void refuseExisting(DedupCandidate existing) {
        throw duplicateRefusal(existing);
    }

    
    private void requireProject(long projectId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE id = ? AND status = 1", Integer.class, projectId);
        if (count == null || count == 0) {
            throw new ServiceException("项目 %d 不存在或已归档，请在「项目管理」确认项目状态后重新选择",
                    BizErrorCode.NOT_FOUND, projectId);
        }
    }

    private RequirementInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RequirementInfo(rs.getLong("id"), rs.getLong("project_id"), rs.getInt("source"),
                rs.getString("source_id"), rs.getString("title"), rs.getString("acceptance_criteria"),
                rs.getInt("status"), rs.getString("chapter"), rs.getLong("created_by"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
                rs.getInt("version"), readAnchor(rs.getString("source_anchor")),
                rs.getString("fingerprint"));
    }

    /**
 * Anchor column read-back . A null/blank/malformed value all
 * degrade to "unanchored" - the generation chain then injects nothing rather
 * than guessing a source, and one corrupt row can never break the list page.
 */
    private static RequirementSourceAnchor readAnchor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = SNAPSHOT_MAPPER.readTree(raw);
            Long documentId = node.path("documentId").isNumber() ? node.path("documentId").asLong() : null;
            java.util.List<Long> chunkIds = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode id : node.path("chunkIds")) {
                if (id.isNumber() || id.isTextual()) {
                    chunkIds.add(id.asLong());
                }
            }
            com.fasterxml.jackson.databind.JsonNode evidenceNode = node.path("evidence");
            String evidence = evidenceNode.isTextual() ? evidenceNode.asText() : null;
            if (documentId == null && chunkIds.isEmpty() && (evidence == null || evidence.isBlank())) {
                return null;
            }
            return new RequirementSourceAnchor(documentId, java.util.List.copyOf(chunkIds), evidence);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Evidence anchor as JSONB text; a null anchor writes SQL NULL (zero drift). */
    private static String anchorJson(RequirementSourceAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("documentId", anchor.documentId());
        payload.put("chunkIds", anchor.chunkIds() == null ? List.of() : anchor.chunkIds());
        payload.put("evidence", anchor.evidence());
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("evidence anchor is not serialisable", e);
        }
    }
}

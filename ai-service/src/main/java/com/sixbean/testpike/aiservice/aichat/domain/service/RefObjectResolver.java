package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.ExecutionLabels;
import com.sixbean.testpike.aiservice.aichat.domain.component.RefResolution;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetch-and-verify of the dialog @-mention chips :
 * each canonicalised (kind, id, title) triple is re-read from the core
 * internal doors, project-slice checked against the session's project, and
 * rendered as one digest line for the system prompt.
 * <p>Posture notes:
 * <ul>
 * <li>the (kind,id) validation point upstream is core's ChatObjectRefs —
 * this class never re-checks the wire shape, it checks FACTS: the row must
 * exist and live inside the session's project segment (H1 red line — a chip
 * pointing at another project's row is a horizontal-access attempt and
 * refuses the run, never silently drops);</li>
 * <li>defect rows carry no projectId, so the segment check runs through the
 * owning case (DefectInfo.caseId → case row), reusing the same door;</li>
 * <li>digest titles come from the fetched row, not the chip label — a
 * renamed object must not ride the prompt under a stale name;</li>
 * <li>each line is length-bounded so ten pinned chips stay a small,
 * predictable segment of the prompt budget (the per-round guard seam itself
 * belongs to the AP batch's PromptSizeGuard wiring).</li>
 * </ul>
 */
@Service
public class RefObjectResolver {

    /** Prompt-weight bound of one chip's digest line. */
    private static final int LINE_MAX_CHARS = 400;

    /** K8 knob: how many newest review opinions enter the digest. */
    static final String KEY_REVIEW_MAX = "ai.chat.pinned-grounding.review-max";
    /** K8 knob: how many newest failed/timed-out runs enter the digest. */
    static final String KEY_FAILURE_MAX = "ai.chat.pinned-grounding.failure-max";
    private static final int DEFAULT_REVIEW_MAX = 3;
    private static final int DEFAULT_FAILURE_MAX = 2;
    /** Length bound of one opinion / one failure row inside the digest. */
    private static final int DETAIL_MAX_CHARS = 300;

    private final CoreGateway coreGateway;

    public RefObjectResolver(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    /**
 * Resolve every chip or refuse with the first failing reason. An empty /
 * null list is a plain pass-through (most turns pin nothing).
 * <p>Legacy two-arg seam: the actor-less tool door verdict. Dialog runs
 * always go through the actor-aware overload below — the mention chips of
 * verify against the SAME five-way-gated assembly set the composer
 * offered, so the palette and the run can never disagree on visibility.
 */
    public RefResolution resolve(long projectId, List<ChatObjectRef> refs) {
        return resolve(projectId, 0L, refs);
    }

    /** Actor-aware resolution: {@code actorId} drives the tool assembly gate. */
    public RefResolution resolve(long projectId, long actorId, List<ChatObjectRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return RefResolution.EMPTY;
        }
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            ChatObjectRef ref = refs.get(i);
            try {
                block.append("[R").append(i + 1).append("] ")
                        .append(bound(digestOf(projectId, actorId, ref))).append('\n');
            } catch (ServiceException unreadable) {
                // Named refusal: the user must see WHICH chip failed and why.
                return RefResolution.refused("引用的对象「" + ref.title() + "」无法核验："
                        + unreadable.getMessage());
            } catch (RuntimeException doorDown) {
                return RefResolution.refused("引用的对象「" + ref.title()
                        + "」暂时读不到（核心服务未响应），请稍后重试。");
            }
        }
        return RefResolution.resolved(block.toString());
    }

    private String digestOf(long projectId, long actorId, ChatObjectRef ref) {
        return switch (ref.kind()) {
            case "case" -> {
                Map<String, Object> row = coreGateway.getCase(ref.id());
                requireSameProject(row, projectId);
                // pinned-case grounding: the chip block must answer the
                // three questions the model otherwise sends back to the user
                // ("which version is current? what did reviewers ask for? is
                // it failing right now?") — all read-only enrichments, each
                // fail-soft so a down side-door degrades one line, never the
                // run (the main getCase door keeps its refusal verdict).
                StringBuilder digest = new StringBuilder("测试用例「")
                        .append(text(row.get("title"), ref.title()))
                        .append("」（编号 ").append(text(row.get("caseNo"), "-"))
                        .append("，当前版本 v").append(text(row.get("version"), "-"))
                        .append("，更新于 ").append(text(row.get("updatedAt"), "-")).append("）");
                appendReviewOpinions(digest, ref.id());
                appendFailureSummary(digest, projectId, ref.id());
                yield digest.toString();
            }
            case "batch" -> {
                Map<String, Object> row = coreGateway.getBatch(ref.id());
                requireSameProject(row, projectId);
                yield "执行批次「" + text(row.get("name"), ref.title())
                        + "」（批次号 " + text(row.get("batchNo"), "-") + "）";
            }
            case "doc" -> {
                Map<String, Object> row = coreGateway.getDocument(ref.id());
                requireSameProject(row, projectId);
                yield "知识文档「" + text(row.get("name"), ref.title())
                        + "」（文件类型 " + text(row.get("fileType"), "-") + "）";
            }
            case "defect" -> {
                Map<String, Object> row = coreGateway.getDefect(ref.id());
                Long caseId = asLong(row.get("caseId"));
                if (caseId == null) {
                    throw new ServiceException("缺陷关联缺少所属用例，无法核验归属");
                }
                Map<String, Object> owningCase = coreGateway.getCase(caseId);
                requireSameProject(owningCase, projectId);
                yield "缺陷「" + ref.title() + "」（缺陷标识 "
                        + text(row.get("defectKey"), "-") + "，Jira 状态 "
                        + text(row.get("defectStatus"), "-") + "，所属用例「"
                        + text(owningCase.get("title"), "-") + "」）";
            }
            case "skill" -> {
                // AO batch : verified against the project SKILL assembly —
                // the very set S5 injection consumes (published filter and
                // read order single-sourced core-side, R34 system-level rows
                // included there). No second lookup chain, no second verdict.
                SkillAssemblyRemoteItem row = coreGateway.getProjectAssembly(projectId).stream()
                        .filter(item -> item.skillId() == ref.id())
                        .findFirst()
                        .orElseThrow(() -> new ServiceException(
                                "该技能不在当前项目的装配候选集内（可能已下架或不属于本项目）"));
                yield "技能「" + text(row.name(), ref.title())
                        + "」（生效版本 v" + row.version() + "）";
            }
            case "tool" -> {
                // Same posture on the tool side: the actor-aware five-way gate
                // verdict of the FC assembly (membership ∩ published ∩ enabled
                // ∩ exec switch ∩ R34 pass-through) decides visibility — the
                // chip only rides if the run could actually call the tool.
                ToolFcAssemblyRemoteItem row =
                        coreGateway.getProjectFcAssembly(projectId, actorId == 0L ? null : actorId)
                                .stream()
                                .filter(item -> item.toolId() == ref.id())
                                .findFirst()
                                .orElseThrow(() -> new ServiceException(
                                        "该工具不在当前项目对本人的可调用候选集内（可能已下架、停用或不属于本项目）"));
                yield "工具「" + text(row.name(), ref.title()) + "」（类型 "
                        + (row.type() == 2 ? "可执行" : "文档类")
                        + (row.write() ? "，写语义需二次确认" : "") + "）";
            }
            default -> throw new ServiceException("引用类型不受支持：" + ref.kind());
        };
    }

    /** H1 segment check on the fetched row (never trust the chip's own fields). */
    private static void requireSameProject(Map<String, Object> row, long projectId) {
        Long rowProject = asLong(row.get("projectId"));
        if (rowProject == null || rowProject != projectId) {
            throw new ServiceException("该对象不属于当前会话的项目，无法引用（跨项目引用已拒绝）");
        }
    }

    /**
 * [R1a] review-opinion lines: newest review-max rows with a non-blank
 * remark (core returns the log time-descending, the read order is single-
 * refusing — enrichment may fail soft, the main chip verification may not.
 */
    private void appendReviewOpinions(StringBuilder digest, long caseId) {
        int cap = intConfig(KEY_REVIEW_MAX, DEFAULT_REVIEW_MAX);
        List<Map<String, Object>> logs;
        try {
            logs = coreGateway.getCaseReviewLogs(caseId);
        } catch (RuntimeException door) {
            digest.append("\n[R1a] 评审意见暂不可读");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> log : logs) {
            String remark = text(log.get("remark"), "");
            if (remark.isBlank()) {
                continue;
            }
            if (lines.size() >= cap) {
                break; // cap counts opinions that actually enter the block
            }
            lines.add(text(log.get("operatorName"), "-") + "「" + text(log.get("action"), "-")
                    + "」" + text(log.get("createdAt"), "-") + "：" + clip(remark));
        }
        if (lines.isEmpty()) {
            return;
        }
        digest.append("\n[R1a] 评审意见：").append(String.join("；", lines));
    }

    /**
 * [R1b] failure summary: the newest failure-max history rows whose status
 * errorDetail. Attribution words come from the shared ExecutionLabels map
 * (R40 — the [E] diagnosis block and this line must not diverge). AI class
 */
    private void appendFailureSummary(StringBuilder digest, long projectId, long caseId) {
        int cap = intConfig(KEY_FAILURE_MAX, DEFAULT_FAILURE_MAX);
        Map<String, Object> page;
        try {
            page = coreGateway.getCaseExecHistory(caseId, projectId, 1, cap);
        } catch (RuntimeException door) {
            digest.append("\n[R1b] 失败摘要暂不可读");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : historyRows(page)) {
            Long status = asLong(row.get("status"));
            if (status == null || (status != 4L && status != 5L)) {
                continue;
            }
            String attribution = attributionOf(row);
            lines.add(text(row.get("finishedAt"), "-") + " " + text(row.get("batchName"), "试运行")
                    + " " + ExecutionLabels.statusLabel(status.intValue())
                    + "·" + attribution + "：" + clip(text(row.get("errorDetail"), "-")));
            if (lines.size() >= cap) {
                break;
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        digest.append("\n[R1b] 失败摘要：").append(String.join("；", lines));
    }

    /** PageResult wire rows of the history door (list field, defensive empty). */
    private static List<Map<String, Object>> historyRows(Map<String, Object> page) {
        if (page == null || !(page.get("list") instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) map;
                rows.add(row);
            }
        }
        return rows;
    }

    private static String attributionOf(Map<String, Object> row) {
        Long aiClass = asLong(row.get("aiFailureClass"));
        if (aiClass != null) {
            return ExecutionLabels.classLabel(aiClass.intValue());
        }
        Long errorType = asLong(row.get("errorType"));
        if (errorType == null) {
            return "未归类";
        }
        return switch (errorType.intValue()) {
            case 1 -> "产品缺陷";
            case 2 -> "定位错误";
            case 3 -> "环境问题";
            case 4 -> "依赖失败";
            default -> "未知(" + errorType + ")";
        };
    }

    /** K8 hot-read with seeded fallback; a down config door never fails a turn over a knob. */
    private int intConfig(String key, int fallback) {
        try {
            String raw = coreGateway.getSysConfig(key);
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (RuntimeException doorOrValue) {
            // NumberFormatException is a RuntimeException — one arm covers both.
            return fallback;
        }
    }

    /** Per-opinion/row length bound (300 chars per the batch ruling). */
    private static String clip(String raw) {
        String trimmed = raw.strip();
        return trimmed.length() <= DETAIL_MAX_CHARS ? trimmed
                : trimmed.substring(0, DETAIL_MAX_CHARS) + "…（已截断）";
    }

    private static Long asLong(Object raw) {
        return raw instanceof Number number ? number.longValue() : null;
    }

    private static String text(Object raw, String fallback) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return fallback;
        }
        return String.valueOf(raw);
    }

    private static String bound(String line) {
        return line.length() <= LINE_MAX_CHARS ? line : line.substring(0, LINE_MAX_CHARS);
    }
}

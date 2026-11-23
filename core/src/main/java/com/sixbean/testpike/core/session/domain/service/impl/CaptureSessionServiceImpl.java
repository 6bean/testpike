package com.sixbean.testpike.core.session.domain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.core.session.api.dto.CaptureCandidateMetadata;
import com.sixbean.testpike.core.session.api.dto.CaptureCommitValues;
import com.sixbean.testpike.core.session.api.dto.CaptureCommittedEntry;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionClaim;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInfo;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInput;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionReport;
import com.sixbean.testpike.core.session.api.dto.CaptureSlotStageInput;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.component.LoginStateEntriesComposer;
import com.sixbean.testpike.core.session.domain.component.SessionGovernanceKeys;
import com.sixbean.testpike.core.session.domain.component.SessionStateSupport;
import com.sixbean.testpike.core.session.domain.service.CaptureSessionService;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * three-round envelope: open here, executor write-back and commit join in the
 * following packages). Red lines enforced at this single point:
 *
 * <ul>
 * <li>red line 2 — the origin whitelist must be non-empty and every entry
 * must parse as an origin; an empty scope is a refusal, never "all";</li>
 * <li>red line 4 — the debug endpoint string is only ever stored inside the
 * TTL-bound Redis session payload; nothing here touches PostgreSQL;</li>
 * <li>red line 6 / D2 — {@code host-window} mode is a shell behind the
 * factory-off switch {@code session.headless-host-capture.enabled};
 * while the switch is off the request is refused BEFORE any session is
 * created, with wording naming both the settings page path and the
 * parameter (R32 guidance is part of the deliverable, not an option).</li>
 * </ul>
 */
@Service
public class CaptureSessionServiceImpl implements CaptureSessionService {

    /** Main path: the executor attaches to the operator's own browser session. */
    public static final String MODE_LOCAL_DIRECT = "local-direct";

    /** Secondary path shell: xvfb-hosted capture window (body delivered separately, D6). */
    public static final String MODE_HOST_WINDOW = "host-window";

    /** Login-success anchor types (three-way choice frozen by the AV design doc). */
    public static final List<String> ANCHOR_KINDS = List.of("url-prefix", "target-element", "token-key");

    /** Transient-channel status words (wizard-visible state; no executor text rides along). */
    static final String STATUS_COLLECTING = "collecting";
    static final String STATUS_CANDIDATES_READY = "candidates-ready";
    static final String STATUS_COLLECT_FAILED = "collect-failed";
    static final String STATUS_COLLECT_EMPTY = "collect-empty";
    /** Wizard confirmed the checked subset; the next claim carries commitSelection. */
    static final String STATUS_COMMIT_REQUESTED = "commit-requested";

    private final CaptureSessionStore store;
    private final SessionGovernanceKeys governanceKeys;
    private final SessionStateSupport sessionStateSupport;
    private final LoginStateService loginStateService;
    private final LoginStateEntriesComposer entriesComposer;
    private final ObjectMapper objectMapper;

    public CaptureSessionServiceImpl(CaptureSessionStore store,
                                     SessionGovernanceKeys governanceKeys,
                                     SessionStateSupport sessionStateSupport,
                                     LoginStateService loginStateService,
                                     LoginStateEntriesComposer entriesComposer,
                                     ObjectMapper objectMapper) {
        this.store = store;
        this.governanceKeys = governanceKeys;
        this.sessionStateSupport = sessionStateSupport;
        this.loginStateService = loginStateService;
        this.entriesComposer = entriesComposer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String open(long projectId, CaptureSessionInput input) {
        if (input == null) {
            throw new ServiceException("缺少采集会话参数，请从登录态页重新发起采集。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String mode = normalizeMode(input.mode());
        if (MODE_HOST_WINDOW.equals(mode) && !governanceKeys.headlessHostCaptureEnabled()) {
            // Kernel frame owns the switched-off sentence (settings path + literal
            // config key); this site only appends its own honest alternative.
            throw new ServiceException(DenialGuidanceFrame.switchClosedAfterAttempt(
                    "服务端采集窗口",
                    "或改用本机直连采集（mode=" + MODE_LOCAL_DIRECT + "）。",
                    "session.headless-host-capture.enabled"),
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> problems = new ArrayList<>();
        if (isBlank(input.stateName())) {
            problems.add("登录态名称不能为空");
        }
        List<String> origins = normalizeOrigins(input.scopeOrigins(), problems);
        if (!ANCHOR_KINDS.contains(input.anchorKind())) {
            problems.add("登录成功判定方式只能是 url-prefix、target-element 或 token-key");
        }
        if (isBlank(input.anchorValue())) {
            problems.add("登录成功判定值不能为空");
        }
        if (isBlank(input.debugEndpoint())) {
            problems.add("缺少浏览器调试端点，请确认本机浏览器已开启远程调试后重新发起");
        }
        if (!problems.isEmpty()) {
            throw new ServiceException(String.join("；", problems) + "。",
                    BizErrorCode.VALIDATION_ERROR);
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("stateName", input.stateName().trim());
        payload.put("scopeOrigins", origins);
        payload.put("anchorKind", input.anchorKind());
        payload.put("anchorValue", input.anchorValue().trim());
        payload.put("debugEndpoint", input.debugEndpoint().trim());
        payload.put("mode", mode);
        payload.put("status", STATUS_COLLECTING);
        payload.put("attempt", 0L);
        payload.put("createdAtEpochSecond", System.currentTimeMillis() / 1000);
        store.save(sessionId, payload);
        store.enqueue(sessionId);
        return sessionId;
    }

    /**
     * Hand one queued session to an executor. Sessions whose payload is gone
     * (window elapsed, or the operator abandoned them) are skipped instead of
     * being handed over, which doubles as the cleanup path for the queue.
     */
    @Override
    public CaptureSessionClaim claim(long executorId) {
        for (String sessionId = store.claimPending(); sessionId != null; sessionId = store.claimPending()) {
            Map<String, Object> payload = gateLive(store.load(sessionId), sessionId);
            if (payload == null) {
                continue;
            }
            long attempt = asLong(payload.get("attempt")) + 1;
            payload.put("attempt", attempt);
            payload.put("lastExecutorId", executorId);
            store.save(sessionId, payload);
            return new CaptureSessionClaim(
                    sessionId,
                    asLong(payload.get("projectId")),
                    asText(payload.get("stateName")),
                    asStringList(payload.get("scopeOrigins")),
                    asText(payload.get("anchorKind")),
                    asText(payload.get("anchorValue")),
                    asText(payload.get("debugEndpoint")),
                    asText(payload.get("mode")),
                    governanceKeys.validateTimeoutMs(),
                    attempt,
                    asStringList(payload.get("commitSelection")));
        }
        return null;
    }

    /**
     * Store one capture round. Only the four metadata fields are serialized —
     * the record carrying a value simply has no such field (red line 3), and a
     * row whose origin is not on the session whitelist is dropped here as well
     * (red line 2: the executor-side filter is not trusted blindly).
     */
    @Override
    public long report(String sessionId, CaptureSessionReport report) {
        if (report == null) {
            return -1L;
        }
        Map<String, Object> payload = gateLive(store.load(sessionId), sessionId);
        if (payload == null) {
            return -1L;
        }
        List<String> scopeOrigins = asStringList(payload.get("scopeOrigins"));
        List<String> rows = new ArrayList<>();
        for (CaptureCandidateMetadata candidate : safeCandidates(report)) {
            if (isBlank(candidate.name()) || !sessionStateSupport.originAllowed(scopeOrigins, candidate.origin())) {
                continue;
            }
            rows.add(toJson(candidate));
        }
        String status;
        if (!rows.isEmpty()) {
            store.appendCandidates(sessionId, rows);
            status = STATUS_CANDIDATES_READY;
        } else {
            status = isBlank(report.failureReason()) ? STATUS_COLLECT_EMPTY : STATUS_COLLECT_FAILED;
        }
        payload.put("status", status);
        store.save(sessionId, payload);
        if (STATUS_COLLECT_FAILED.equals(status) || STATUS_COLLECT_EMPTY.equals(status)) {
            // The human may still be mid-login: keep the session alive and let
            // the next idle round retry inside the same window.
            store.enqueue(sessionId);
        }
        return rows.size();
    }

    @Override
    public CaptureSessionInfo find(String sessionId) {
        Map<String, Object> payload = gateLive(store.load(sessionId), sessionId);
        if (payload == null) {
            throw new ServiceException("采集会话已过期或不存在——采集窗口由参数 "
                    + SessionGovernanceKeys.KEY_CAPTURE_TIMEOUT_MINUTES
                    + " 控制（当前 " + governanceKeys.captureTimeoutMinutes() + " 分钟），请重新发起采集。",
                    BizErrorCode.NOT_FOUND);
        }
        long projectId = payload.get("projectId") instanceof Number n ? n.longValue() : 0L;
        return new CaptureSessionInfo(
                sessionId,
                projectId,
                asText(payload.get("stateName")),
                asStringList(payload.get("scopeOrigins")),
                asText(payload.get("anchorKind")),
                asText(payload.get("anchorValue")),
                asText(payload.get("mode")),
                asText(payload.get("status")),
                store.candidateCount(sessionId),
                payload.get("createdAtEpochSecond") instanceof Number n ? n.longValue() : 0L);
    }

    @Override
    public String stageScreenshotEntries(CaptureSlotStageInput input) {
        if (input == null || input.channel() == null || input.channel().isBlank()) {
            throw new ServiceException("截图解析结果暂存缺少渠道标识，暂存已拒绝。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.sessionId() <= 0) {
            throw new ServiceException("截图解析结果暂存缺少归属会话，暂存已拒绝。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<CaptureCommittedEntry> entries = input.entries();
        if (entries == null || entries.isEmpty()) {
            throw new ServiceException("截图解析结果没有任何可暂存条目，暂存已拒绝（请重新粘贴截图）。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (entries.size() > CaptureSlotStageInput.MAX_ENTRIES) {
            throw new ServiceException("截图解析结果超过单次上限 " + CaptureSlotStageInput.MAX_ENTRIES
                    + " 条，暂存已拒绝（请按面板分区分别截图）。", BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> names = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (CaptureCommittedEntry entry : entries) {
            if (entry == null || entry.name() == null || entry.name().isBlank()) {
                throw new ServiceException("截图解析结果含空白名称行，暂存已拒绝。",
                        BizErrorCode.VALIDATION_ERROR);
            }
            // The checklist on the draft card authorizes by name — a duplicated
            // name would let one check silently cover two rows.
            if (!names.add(entry.name())) {
                throw new ServiceException("截图解析结果存在同名条目「" + entry.name()
                        + "」，勾选授权会产生歧义，暂存已拒绝。", BizErrorCode.VALIDATION_ERROR);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", entry.kind());
            row.put("name", entry.name());
            row.put("origin", entry.origin());
            row.put("value", entry.value());
            row.put("expiresAtEpochSecond", entry.expiresAtEpochSecond());
            rows.add(row);
        }
        String token = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", input.channel());
        payload.put("sessionId", input.sessionId());
        payload.put("entries", rows);
        store.save(token, payload);
        return token;
    }

    @Override
    public List<String> stagedNames(String token) {
        // Piece F emitter probe: metadata-only read of one screenshot slot.
        // The value slots stay parked — this door answers names, in staged
        // order, and nothing else (piece E red line). Absent/expired slot
        // and malformed rows answer empty, never an exception: the caller
        // turns an empty name domain into an honest card refusal.
        Map<String, Object> slot = token == null || token.isBlank()
                ? null : store.load(token);
        if (slot == null || !(slot.get("entries") instanceof List<?> rows)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map && map.get("name") != null) {
                names.add(String.valueOf(map.get("name")));
            }
        }
        return names;
    }

    @Override
    public void abandon(String sessionId) {
        store.dequeue(sessionId);
        store.remove(sessionId);
    }

    @Override
    public List<CaptureCandidateMetadata> candidates(String sessionId) {
        // find() is the single source of the expiry wording and the liveness gate.
        find(sessionId);
        List<CaptureCandidateMetadata> out = new ArrayList<>();
        for (String row : store.candidateRows(sessionId)) {
            CaptureCandidateMetadata parsed = fromJson(row);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    @Override
    public int commit(String sessionId, List<String> selectedNames) {
        find(sessionId);
        Set<String> available = new LinkedHashSet<>();
        for (CaptureCandidateMetadata row : candidates(sessionId)) {
            if (!isBlank(row.name())) {
                available.add(row.name());
            }
        }
        Set<String> selected = new LinkedHashSet<>();
        if (selectedNames != null) {
            for (String name : selectedNames) {
                if (name != null && available.contains(name.trim())) {
                    selected.add(name.trim());
                }
            }
        }
        if (selected.isEmpty()) {
            throw new ServiceException("勾选的候选条目不存在或已过期——请在候选列表勾选有效条目后重新确认入册；"
                    + "列表为空时请重新发起采集。", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> payload = store.load(sessionId);
        if (payload == null || windowElapsed(payload)) {
            if (payload != null) {
                store.remove(sessionId);
            }
            throw new ServiceException("采集会话已过期，请重新发起采集。", BizErrorCode.NOT_FOUND);
        }
        payload.put("commitSelection", List.copyOf(selected));
        payload.put("status", STATUS_COMMIT_REQUESTED);
        store.save(sessionId, payload);
        // The reverse signal rides the same hand-off queue the forward one uses
        store.enqueue(sessionId);
        return selected.size();
    }

    @Override
    public long commitValues(String sessionId, CaptureCommitValues values) {
        Map<String, Object> payload = store.load(sessionId);
        if (payload == null) {
            return -1L;
        }
        if (values != null && !isBlank(values.failureReason())) {
            // Browser session gone (logout / window closed): honest invalidation.
            // Terminal delete makes the wizard fall back to the same expiry
            // wording a timeout would show — never silence, never a fake row.
            store.dequeue(sessionId);
            store.remove(sessionId);
            return -1L;
        }
        if (windowElapsed(payload)) {
            store.remove(sessionId);
            return -1L;
        }
        List<String> selection = asStringList(payload.get("commitSelection"));
        if (selection.isEmpty()) {
            // Values without a wizard confirmation are refused outright: the
            return -1L;
        }
        List<CaptureCommittedEntry> entries =
                values == null || values.entries() == null ? List.of() : values.entries();
        List<String> scopeOrigins = asStringList(payload.get("scopeOrigins"));
        List<CaptureCommittedEntry> accepted = new ArrayList<>();
        for (CaptureCommittedEntry entry : entries) {
            // Server-side re-filter of the checked subset and the origin
            // whitelist (red line 2 double gate); blank values are no values.
            if (entry == null || isBlank(entry.name()) || isBlank(entry.value())
                    || !selection.contains(entry.name())
                    || !sessionStateSupport.originAllowed(scopeOrigins, entry.origin())) {
                continue;
            }
            accepted.add(entry);
        }
        if (accepted.isEmpty()) {
            // Nothing came back but the browser answered: keep the session and
            // re-queue the same signal for a retry inside the window.
            store.enqueue(sessionId);
            return 0L;
        }
        long projectId = asLong(payload.get("projectId"));
        // Ledger-row composition is single-sourced in LoginStateEntriesComposer
        // (shared with the screenshot draft landing, piece D — R40: one
        // composition, never a second copy).
        loginStateService.register(projectId, entriesComposer.compose(
                asText(payload.get("stateName")),
                asText(payload.get("anchorKind")), asText(payload.get("anchorValue")),
                accepted, scopeOrigins,
                "登录态采集入册：勾选 " + accepted.size() + " 条目（浏览器会话直采）"));
        store.dequeue(sessionId);
        store.remove(sessionId);
        return accepted.size();
    }

    /** Metadata row back to its wizard shape; a malformed row is treated as absent. */
    private CaptureCandidateMetadata fromJson(String row) {
        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(row, new TypeReference<Map<String, Object>>() {
                    });
            Long expires = parsed.get("expiresAtEpochSecond") instanceof Number n ? n.longValue() : null;
            return new CaptureCandidateMetadata(asText(parsed.get("kind")), asText(parsed.get("name")),
                    asText(parsed.get("origin")), expires);
        } catch (Exception e) {
            return null;
        }
    }

    /** Capture window (hot key) measured from session creation; elapsed = never hand over. */
    private boolean windowElapsed(Map<String, Object> payload) {
        long createdAt = asLong(payload.get("createdAtEpochSecond"));
        long windowSeconds = governanceKeys.captureTimeoutMinutes() * 60L;
        return System.currentTimeMillis() / 1000 - createdAt >= windowSeconds;
    }

    /**
 * Single liveness gate : a key sitting in Redis is NOT proof of a
 * live session — the window is. An elapsed payload is terminally removed
 * (R57 exit sweep, every face does it idempotently) and answered as
 * absent, so claim, report and every read face (detail + candidates)
 * void the same way with the same wording upstream.
 */
    private Map<String, Object> gateLive(Map<String, Object> payload, String sessionId) {
        if (payload == null) {
            return null;
        }
        if (windowElapsed(payload)) {
            store.remove(sessionId);
            return null;
        }
        return payload;
    }

    private static List<CaptureCandidateMetadata> safeCandidates(CaptureSessionReport report) {
        return report.candidates() == null ? List.of() : report.candidates();
    }

    /** Metadata-only projection; the record has no value field, so nothing can leak here. */
    private String toJson(CaptureCandidateMetadata candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", candidate.kind());
        row.put("name", candidate.name());
        row.put("origin", candidate.origin());
        row.put("expiresAtEpochSecond", candidate.expiresAtEpochSecond());
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException e) {
            throw new ServiceException("候选条目无法记录，请重新发起采集。", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** Blank mode rides the main path; anything unknown is refused (fail-closed). */
    private static String normalizeMode(String raw) {
        if (isBlank(raw)) {
            return MODE_LOCAL_DIRECT;
        }
        String mode = raw.trim();
        if (!MODE_LOCAL_DIRECT.equals(mode) && !MODE_HOST_WINDOW.equals(mode)) {
            throw new ServiceException("采集方式仅支持 " + MODE_LOCAL_DIRECT + " 或 "
                    + MODE_HOST_WINDOW + "。", BizErrorCode.VALIDATION_ERROR);
        }
        return mode;
    }

    /** Origin whitelist gate (red line 2): trimmed non-empty entries, else refuse. */
    private static List<String> normalizeOrigins(List<String> raw, List<String> problems) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            problems.add("来源域名白名单不能为空");
            return out;
        }
        for (String origin : raw) {
            if (!isBlank(origin)) {
                out.add(origin.trim());
            }
        }
        if (out.isEmpty()) {
            problems.add("来源域名白名单不能为空");
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String asText(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static List<String> asStringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        return List.of();
    }
}

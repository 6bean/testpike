package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.session.api.dto.CaptureCommittedEntry;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.component.LoginStateEntriesComposer;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.component.DraftLandContext;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Login-state registration strategy behind the generic draft door (screenshot
 * capture batch piece D): one confirmed card files the checked subset of
 * a server-parsed DevTools panel screenshot through the SAME
 * {@code LoginStateService.register} ledger chain the CDP capture wizard uses,
 * so vault encryption, D9 same-name renewal, replay verification and the
 * four-state light stay single-sourced and the dialog is structurally unable
 * to bypass one gate.
 *
 * <p>Secret posture (piece E red line): the draft payload carries only display
 * metadata and the check list — token values never ride it. The freshly parsed
 * values live in the TTL-bound transient capture channel
 * ({@link CaptureSessionStore}, same key family and window as the CDP wizard's
 * commit round); this strategy re-reads them at landing time and deletes the
 * slot on success. An expired or absent slot refuses with the re-capture
 * wording instead of resurrecting stale material.
 *
 * <p>Attribution guard (H1): the owning project comes from the chat session,
 * and the staging slot records the session that produced it — a card naming
 * another session's slot is refused before anything is registered. A selected
 * name the slot does not carry fails the whole landing closed: the check list
 * is an explicit authorization act, a silently dropped or added row is not.
 */
@Service
public class LoginStateDraftStrategy implements DraftWriteStrategy {

    private final LoginStateService loginStateService;
    private final CaptureSessionStore captureSessionStore;
    private final LoginStateEntriesComposer entriesComposer;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoginStateDraftStrategy(LoginStateService loginStateService,
                                   CaptureSessionStore captureSessionStore,
                                   LoginStateEntriesComposer entriesComposer,
                                   ChatSessionService sessionService,
                                   JdbcTemplate jdbcTemplate) {
        this.loginStateService = loginStateService;
        this.captureSessionStore = captureSessionStore;
        this.entriesComposer = entriesComposer;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.LOGIN_STATE_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        DraftLandContext ctx = DraftLandContext.open(draftRow, sessionService, objectMapper,
                "AI 登录态登记草案内容无法解析，确认入册已拒绝（请重新让 AI 生成提案）");
        JsonNode body = ctx.body();
        long draftId = ctx.draftId(), sessionId = ctx.sessionId(), projectId = ctx.projectId();
        String token = body.path("stagingToken").asText("");
        String stateName = body.path("stateName").asText("");
        if (token.isBlank() || stateName.isBlank()) {
            throw new ServiceException(
                    "AI 登录态登记草案缺少解析凭据或登录态名称，无法入册"
                            + "（请重新粘贴截图让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> selected = selectedNames(body.path("entries"));
        if (selected.isEmpty()) {
            throw new ServiceException(
                    "AI 登录态登记草案没有勾选任何候选条目，入册已拒绝"
                            + "（勾选即授权，请在草案卡上至少勾选一条后重新确认）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> slot = captureSessionStore.load(token);
        if (slot == null || slot.get("entries") == null) {
            throw new ServiceException(
                    "截图解析结果已过期，登录态未入册（请重新粘贴 DevTools 面板截图生成新提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // H1: the slot is anchored to the session that staged it — a card
        // forged onto another session's token drains nothing.
        long slotSessionId = slot.get("sessionId") instanceof Number n
                ? n.longValue() : Long.parseLong(String.valueOf(slot.get("sessionId")));
        if (slotSessionId != sessionId) {
            throw new ServiceException(
                    "AI 登录态登记草案指向的解析会话与当前会话不一致，入册已拒绝"
                            + "（请在生成提案的对话中直接确认，或重新粘贴截图生成新提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, CaptureCommittedEntry> staged = stagedEntries(slot.get("entries"));
        List<CaptureCommittedEntry> checked = new ArrayList<>(selected.size());
        for (String name : selected) {
            CaptureCommittedEntry entry = staged.get(name);
            if (entry == null) {
                // Fail-closed whole-card refusal: the authorization set must
                // equal what the human saw and checked, row by row.
                throw new ServiceException(
                        "AI 登录态登记草案勾选了「" + name + "」，但解析结果中不存在该条目，"
                                + "整单入册已拒绝（请重新粘贴截图生成新提案）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            checked.add(entry);
        }
        long stateRowId = loginStateService.register(projectId, entriesComposer.compose(stateName,
                null, null, checked, List.of(),
                "登录态截图登记：勾选 " + checked.size() + " 条目（DevTools 面板截图解析）"));
        // Terminal state (registered): the transient slot goes with it (R57);
        // refusals above deliberately keep it for a corrected re-confirm
        // inside the same TTL window.
        captureSessionStore.remove(token);
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", stateRowId, draftId);
        // A registered state has no version ledger for this kind — null.
        return null;
    }

    private Set<String> selectedNames(JsonNode entries) {
        Set<String> names = new LinkedHashSet<>();
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                if (entry.path("selected").asBoolean(false)) {
                    String name = entry.path("name").asText("");
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private Map<String, CaptureCommittedEntry> stagedEntries(Object raw) {
        Map<String, CaptureCommittedEntry> byName = new HashMap<>();
        if (raw instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    String name = String.valueOf(map.get("name"));
                    Long expires = map.get("expiresAtEpochSecond") instanceof Number n
                            ? n.longValue() : null;
                    byName.put(name, new CaptureCommittedEntry(
                            String.valueOf(map.get("kind")), name,
                            String.valueOf(map.get("origin")),
                            String.valueOf(map.get("value")), expires));
                }
            }
        }
        return byName;
    }

}

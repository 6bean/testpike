package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.aichat.api.dto.ChatContextWaterlineView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatContextWaterlineService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.config.domain.component.AiCtxKeyRanges;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Context waterline read door : one aggregate over the
 * sealed message ledger plus the two K8 config numbers give the panel the
 * same truth the budget gate and the rolling summary act on — the client
 * never recomputes (CostDashboardView precedent: the door's numbers are the
 * authoritative ones).
 * <p>Config semantics mirror the writer/gate side instead of restating it:
 * the budget key literal's canonical home is the dialog worker's
 * {@code KEY_SESSION_BUDGET_TOKENS} (an absent, unparsable or non-positive
 * value = unlimited, so a broken config box must never render as "budget
 * exhausted"); the keep-recent value is clamped against
 * {@link AiCtxKeyRanges}, the single interval table of the ai.ctx.* group
 * (R40 — no second copy of the ranges here).
 */
@Service
public class ChatContextWaterlineServiceImpl implements ChatContextWaterlineService {

    /** Mirror of the dialog worker's key literal (ChatRunOrchestrator.KEY_SESSION_BUDGET_TOKENS). */
    private static final String KEY_SESSION_BUDGET_TOKENS = "ai.chat.session-budget-tokens";

    /**
     * Numerator + turn count in one pass over the session's sealed messages:
     * SUM(prompt_tokens) is the same ledger number E9 shows, and the user-turn
     * count is the fold vocabulary of the rolling summary.
     */
    private static final String LEDGER_AGGREGATE_SQL = """
            SELECT COALESCE(SUM(m.prompt_tokens), 0) AS prompt_tokens_used,
                   COUNT(*) FILTER (WHERE m.role = 'user') AS user_turns
            FROM tp_ai_chat_message m
            WHERE m.session_id = :sessionId
            """;

    private final NamedParameterJdbcTemplate named;
    private final ConfigService configService;
    private final ChatSessionService sessionService;
    private final ObjectMapper objectMapper;

    public ChatContextWaterlineServiceImpl(NamedParameterJdbcTemplate named,
                                           ConfigService configService,
                                           ChatSessionService sessionService,
                                           ObjectMapper objectMapper) {
        this.named = named;
        this.configService = configService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatContextWaterlineView waterline(long sessionId, long userId) {
        // Ownership first, fail-closed: a stranger triggers no DB or config read.
        ChatSessionInfo session = sessionService.requireOwned(sessionId, userId);
        Map<String, Object> row = named.queryForMap(LEDGER_AGGREGATE_SQL,
                Map.of("sessionId", sessionId));
        long promptTokensUsed = asLong(row.get("prompt_tokens_used"));
        long sealedUserTurns = asLong(row.get("user_turns"));
        long budget = parseBudget(configService.getValue(KEY_SESSION_BUDGET_TOKENS, ""));
        boolean unlimited = budget <= 0;
        int keepRecent = readKeepRecentTurns();
        long foldedTurns = Math.max(0, sealedUserTurns - keepRecent);
        return new ChatContextWaterlineView(promptTokensUsed, unlimited ? 0 : budget,
                unlimited, sealedUserTurns, keepRecent, foldedTurns,
                countPinnedFacts(session.contextJson()));
    }

    /** Non-positive or unparsable cap = unlimited (fail-open on the config read). */
    private static long parseBudget(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int readKeepRecentTurns() {
        String raw = configService.getValue(AiCtxKeyRanges.KEY_SESSION_KEEP_RECENT_TURNS,
                String.valueOf(AiCtxKeyRanges.DEFAULT_SESSION_KEEP_RECENT_TURNS));
        int value;
        try {
            value = Integer.parseInt(raw.strip());
        } catch (Exception e) {
            return AiCtxKeyRanges.DEFAULT_SESSION_KEEP_RECENT_TURNS;
        }
        int[] range = AiCtxKeyRanges.rangeOf(AiCtxKeyRanges.KEY_SESSION_KEEP_RECENT_TURNS);
        if (range != null) {
            value = Math.max(range[0], Math.min(range[1], value));
        }
        return value;
    }

    /**
     * Pinned facts = top-level scalar entries of the session context. The
     * write door already refused any non-whitelisted key and any nested
     * block, so counting scalars here needs no second copy of the key list;
     * a legacy dirty value simply does not count (read path never fails on
     * storage the writer would refuse today).
     */
    private int countPinnedFacts(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(contextJson);
            if (!root.isObject()) {
                return 0;
            }
            int count = 0;
            var fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                // Envelope pointers (route/projectId) ride in on EVERY session;
                // they orient the deep link and are not pinned facts .
                if (ChatSessionService.CONTEXT_ENVELOPE_KEYS.contains(entry.getKey())) {
                    continue;
                }
                JsonNode value = entry.getValue();
                if (value.isTextual() || value.isNumber()) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

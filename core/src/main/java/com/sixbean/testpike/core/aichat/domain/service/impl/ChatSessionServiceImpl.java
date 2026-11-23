package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.aichat.ChatContextKeys;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionListItemInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionPageView;
import com.sixbean.testpike.core.aichat.domain.component.ChatSessionContextMerge;
import com.sixbean.testpike.core.aichat.domain.component.ChatSessionContextMergeOutcome;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.infrastructure.dataobject.ChatSessionRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Dialog session shell implementation . Data path follows the
 * house style: NamedParameterJdbcTemplate + BeanPropertyRowMapper, DO stays in
 * infrastructure and exits through {@link ChatSessionRow#toInfo}.
 * <p>Validation pinned here (single point, adjudication document (context whitelist section)):
 * the context field must be a JSON <em>object</em> — arrays and garbage are
 * refused so "dump everything the user knows into the prompt" is structurally
 * impossible through this door.
 */
@Service
public class ChatSessionServiceImpl implements ChatSessionService {

    private static final String SELECT_COLUMNS = """
            SELECT id, project_id, user_id, title, source,
                   context_json::text AS context_json, status, last_seq, total_tokens
            FROM tp_ai_chat_session
            """;

    /** Bound of tp_ai_chat_session.title — rename refuses beyond it, auto-title cuts below. */
    static final int TITLE_MAX_CHARS = 200;
    /**
 * Shared auto-title bound : the dock previews the first question at
 * the same width, so the list row and the stored auto-title never disagree
 * (one number, referenced by both doors — not two copies that drift).
 */
    public static final int AUTO_TITLE_MAX_CHARS = 60;

    /** Session ledger status values (tp_ai_chat_session.status CHECK domain). */
    private static final int STATUS_ACTIVE = 0;
    private static final int STATUS_ARCHIVED = 1;

    /** S-group contract bound for the history page — the panel never asks for more. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
 * Navigation identifiers a page context may carry (adjudication document,
 * context whitelist section): route path, the session's own project, one
 * object id, one execution id, one change-analysis id (AL batch segment
 * B: the disposition view opens a deep-linked session anchored on the
 * analysis). Anything else at this door is refused.
 */
    private static final Set<String> ALLOWED_CONTEXT_KEYS = ChatContextKeys.DOOR_KEYS;

    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;

    public ChatSessionServiceImpl(NamedParameterJdbcTemplate named, ObjectMapper objectMapper) {
        this.named = named;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatSessionInfo create(long projectId, long userId, String title, int source, String contextJson) {
        if (source < 1 || source > 3) {
            throw new ServiceException("会话入口类型只能是 1 对话坞 / 2 独立页 / 3 徽标深链"
                    + "——页面发起的对话由系统自动携带该参数；外部对接请按正确写法传 source=1、2 或 3 后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // A blank title is legal from on: the session lands as the empty
        // placeholder and the first sealed user question names it (applyAutoTitle).
        // Refusing "no title yet" was punishing the honest flow — open a chat,
        // then ask — and pushed the browser to fake a title before anything was said.
        String storedTitle = title == null || title.isBlank() ? "" : title.strip();
        String normalizedContext = normalizeContextWhitelist(contextJson, projectId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("userId", userId)
                .addValue("title", storedTitle)
                .addValue("source", source)
                .addValue("context", normalizedContext);
        Long id = named.queryForObject(
                "INSERT INTO tp_ai_chat_session (project_id, user_id, title, source, context_json) "
                        + "VALUES (:projectId, :userId, :title, :source, CAST(:context AS jsonb)) "
                        + "RETURNING id", params, Long.class);
        return findById(id);
    }

    @Override
    public ChatSessionInfo requireOwned(long sessionId, long userId) {
        ChatSessionInfo info = findById(sessionId);
        if (info.userId() != userId) {
            throw new ServiceException("该对话会话不属于当前用户，已拒绝访问"
                    + "——请在「历史对话」中选择自己的会话，或点「新建对话」重新开始", BizErrorCode.FORBIDDEN);
        }
        return info;
    }

    @Override
    public ChatSessionInfo findById(long sessionId) {
        // queryForObject throws EmptyResultDataAccessException on a miss (the
        // null guard below was unreachable), which the global handler folded
        // into the generic 50000. Read the row as a list so an unknown id
        // reaches the named NOT_FOUND refusal instead.
        ChatSessionRow row = named.query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", sessionId),
                new BeanPropertyRowMapper<>(ChatSessionRow.class))
                .stream().findFirst().orElse(null);
        if (row == null) {
            throw new ServiceException("对话会话不存在或已删除"
                    + "——请回到「历史对话」重新打开一个会话；列表里没有时点「新建对话」重新开始", BizErrorCode.NOT_FOUND);
        }
        return row.toInfo();
    }

    @Override
    @Transactional
    public ChatSessionInfo rename(long sessionId, long userId, String title) {
        requireOwned(sessionId, userId);
        if (title == null || title.isBlank()) {
            throw new ServiceException("会话标题不能为空"
                    + "——可在「历史对话」列表对该会话点「改名」输入标题；未命名会话会在首个问题后自动命名", BizErrorCode.VALIDATION_ERROR);
        }
        String stripped = title.strip();
        if (stripped.length() > TITLE_MAX_CHARS) {
            throw new ServiceException("会话标题最长 " + TITLE_MAX_CHARS + " 字，当前 "
                    + stripped.length() + " 字，请缩短后再保存"
                    + "——可在「历史对话」列表对该会话点「改名」，改短到 " + TITLE_MAX_CHARS + " 字以内再提交", BizErrorCode.VALIDATION_ERROR);
        }
        // Deliberately no updated_at bump: the history list is ordered by
        // conversation traffic. A rename is bookkeeping — it must not jump a
        // month-old thread to the top of the panel.
        named.update("UPDATE tp_ai_chat_session SET title = :title WHERE id = :sessionId",
                new MapSqlParameterSource().addValue("title", stripped).addValue("sessionId", sessionId));
        return findById(sessionId);
    }

    @Override
    @Transactional
    public ChatSessionInfo archive(long sessionId, long userId) {
        ChatSessionInfo current = requireOwned(sessionId, userId);
        if (current.status() == STATUS_ARCHIVED) {
            // Idempotent by design: the panel's archive action on an already
            // parked session is a no-op, not an error the user must decode.
            return current;
        }
        // Archive never deletes: the row and every sealed turn stay reachable,
        // which is what makes the recycle surface able to bring a thread back.
        named.update("UPDATE tp_ai_chat_session SET status = " + STATUS_ARCHIVED + " WHERE id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId));
        return findById(sessionId);
    }

    @Override
    public void applyAutoTitle(long sessionId, String firstQuestion) {
        if (firstQuestion == null || firstQuestion.isBlank()) {
            return;
        }
        // Only the empty placeholder is named, in one statement: a hand-typed
        // title (and any later turn) can never be overwritten, and a racing
        // second question finds nothing left to name.
        String autoTitle = truncate(firstQuestion.strip(), AUTO_TITLE_MAX_CHARS);
        named.update("UPDATE tp_ai_chat_session SET title = :title, updated_at = now() "
                        + "WHERE id = :sessionId AND title = ''",
                new MapSqlParameterSource().addValue("title", autoTitle).addValue("sessionId", sessionId));
    }

    @Override
    public ChatSessionPageView listVisible(long userId, List<Long> projectIds, Integer status,
                                           Integer source, String keyword, int page, int pageSize) {
        if (page < 1) {
            throw new ServiceException("页码从 1 开始"
                    + "——列表翻页由页面自动携带；接口对接请传「page」为 1 或更大的整数后重试", BizErrorCode.VALIDATION_ERROR);
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ServiceException("每页条数只能是 1~" + MAX_PAGE_SIZE
                    + "——列表默认分页由页面自动携带；接口对接请传「pageSize」为 1~" + MAX_PAGE_SIZE + " 的整数后重试", BizErrorCode.VALIDATION_ERROR);
        }
        // Owner scoping is structural, not a filter the caller may forget: the
        // user id is bound before any predicate is appended (AF-g horizontal line).
        // Optional filters carry an explicit CAST: Postgres cannot infer the type
        // of an untyped null parameter (? IS NULL would be a grammar error).
        StringBuilder where = new StringBuilder(" WHERE s.user_id = :userId")
                // An empty shell (zero messages) is noise the user cannot
                // recognise — it never enters the list ledger .
                .append(" AND EXISTS (SELECT 1 FROM tp_ai_chat_message m WHERE m.session_id = s.id)")
                .append(" AND s.status = CAST(:status AS smallint)");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                // status=null asks for the active surface (0) — archived threads
                // only appear when the caller names status=1 explicitly.
                .addValue("status", status == null ? STATUS_ACTIVE : status);
        if (projectIds != null && !projectIds.isEmpty()) {
            where.append(" AND s.project_id IN (:projectIds)");
            params.addValue("projectIds", projectIds);
        }
        where.append(" AND (CAST(:source AS smallint) IS NULL OR s.source = CAST(:source AS smallint))");
        params.addValue("source", source);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND s.title ILIKE CAST(:pattern AS text)");
            params.addValue("pattern", "%" + likeEscape(keyword.strip()) + "%");
        } else {
            params.addValue("pattern", null);
        }

        long total = named.queryForObject("SELECT count(*) FROM tp_ai_chat_session s" + where,
                params, Long.class);
        List<ChatSessionListItemInfo> rows = named.query(
                "SELECT s.id, s.project_id, s.title, s.source, s.status, s.updated_at, "
                        + "(SELECT count(*) FROM tp_ai_chat_message m WHERE m.session_id = s.id) AS message_count "
                        + "FROM tp_ai_chat_session s" + where
                        + " ORDER BY s.updated_at DESC, s.id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", pageSize).addValue("offset", (long) (page - 1) * pageSize),
                (rs, rowNum) -> new ChatSessionListItemInfo(
                        rs.getLong("id"), rs.getLong("project_id"), rs.getString("title"),
                        rs.getInt("source"), rs.getInt("status"),
                        rs.getTimestamp("updated_at").toLocalDateTime(),
                        rs.getLong("message_count")));
        return new ChatSessionPageView(rows, total, page, pageSize);
    }

    /** Column-width cut (never splits a word boundary we cannot see — a hard cut at the bound). */
    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    /** ILIKE metacharacters in a user-typed keyword are literal, not wildcards. */
    private static String likeEscape(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
 * Whitelist context checks (single validation point for every entry door):
 * must be a JSON object; keys restricted to navigation identifiers (route /
 * projectId / objectId / executionId) so credentials or bulk user data
 * cannot ride in; values stay scalars (an object under a legal key would
 * be a smuggling channel); a projectId pointer that names a different
 * segment than the session's own is refused before the row is written.
 */
    @Override
    public String normalizeContextWhitelist(String contextJson, long sessionProjectId) {
        if (contextJson == null || contextJson.isBlank()) {
            return "{}";
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(contextJson);
        } catch (Exception e) {
            throw new ServiceException("对话上下文不是合法 JSON，已拒绝"
                    + "——页面切换项目/进入深链时由系统自动装配该字段；接口对接请按正确写法传合法 JSON 对象（花括号内给 route、projectId 等字段）后重试", BizErrorCode.VALIDATION_ERROR);
        }
        if (!root.isObject()) {
            throw new ServiceException("对话上下文必须是 JSON 对象（仅路由/项目/对象标识类字段）"
                    + "——接口对接请改成 JSON 对象形态，示例：花括号内依次携带 route、projectId 字段；数组或裸文本会被拒绝",
                    BizErrorCode.VALIDATION_ERROR);
        }
        root.fields().forEachRemaining(entry -> validateContextEntry(entry.getKey(), entry.getValue(), sessionProjectId));
        return contextJson;
    }

    /**
 * fold the question's validated anchor into the session
 * row. One SELECT + the pure merge component + an UPDATE only when the
 * fold actually moved something — replaying the same anchors writes
 * nothing (R28), and the activity cursor (last_seq) is untouched since
 * this fold follows the traffic the ledger rows already sealed.
 */
    @Override
    public void applyQuestionContext(long sessionId, String normalizedQuestionContext) {
        if (normalizedQuestionContext == null || normalizedQuestionContext.isBlank()) {
            return;
        }
        List<String> current = named.query(
                "SELECT context_json::text FROM tp_ai_chat_session WHERE id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId),
                (rs, rowNum) -> rs.getString(1));
        if (current.isEmpty()) {
            return;
        }
        ChatSessionContextMergeOutcome outcome =
                ChatSessionContextMerge.merge(current.get(0), normalizedQuestionContext);
        if (!outcome.changed()) {
            return;
        }
        named.update("UPDATE tp_ai_chat_session SET context_json = CAST(:merged AS jsonb) "
                        + "WHERE id = :sessionId",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("merged", outcome.mergedJson()));
    }

    private void validateContextEntry(String key, JsonNode value, long sessionProjectId) {
        if (!ALLOWED_CONTEXT_KEYS.contains(key)) {
            throw new ServiceException("对话上下文字段 " + key + " 不在允许范围内（仅支持 route、projectId、objectId、executionId、analysisId、documentId）"
                    + "——接口对接请按正确写法装配：只保留上面列出的六个字段名，删掉该字段后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!value.isTextual() && !value.isNumber()) {
            throw new ServiceException("对话上下文字段 " + key + " 只能是文本或数字，不接受嵌套内容"
                    + "——接口对接请按正确写法改成标量值（一段文本或一个数字），去掉对象/数组后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("projectId".equals(key) && value.asLong() != sessionProjectId) {
            throw new ServiceException("对话上下文字段 projectId 与当前项目不一致，跨项目上下文已拒绝"
                    + "——请先在顶栏「选择项目」切到会话所属项目，或点「新建对话」在当前项目重新开始",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}

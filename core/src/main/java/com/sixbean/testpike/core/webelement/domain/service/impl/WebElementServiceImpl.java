package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementVariantInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementPageView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.component.WebElementLifecycle;
import com.sixbean.testpike.core.webelement.domain.component.WebElementPageKeys;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Element ledger doors over tp_web_element (WE batch S0). Anchors persist as
 * an ordered JSONB array; every write consumes the caller's revision token
 * with the shared 40901 posture, every lifecycle move is checked against
 * WebElementLifecycle and appends one tp_ai_task_event row
 * (job_kind=element-ledger) so the tail drawer can replay the audit trail.
 */
@Service
public class WebElementServiceImpl implements WebElementService {

    /** Anchor value domain, matching the tp_web_element column contract.
     * Public so the repair-suggestion door (WE S2) references this one source
     * instead of copying the list (parallel-shared-logic rule). */
    public static final Set<String> ANCHOR_TYPES =
            Set.of("css", "xpath", "text", "placeholder", "alt", "testid", "rel",
                    "role", "label");

    /**
     * Ledger job kind for per-row transition audit events (job_ref = element
     * row id). Separate registered stream from the survey process stream
     * (AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY, job_ref = session id);
     * the two value ranges must never mix.
     */
    public static final String JOB_KIND = "element-ledger";

    private static final String SELECT_COLUMNS = """
            id, project_id, name, page_key, anchors, variants, state, confidence,
            hit_count, miss_count, revision, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebElementPageKeys pageKeys;
    private final WebElementLifecycle lifecycle;
    private final TaskEventWriter events;

    private final com.sixbean.testpike.core.webelement.domain.service.WebElementStormService
            stormService;

    public WebElementServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
                                 WebElementPageKeys pageKeys, WebElementLifecycle lifecycle,
                                 TaskEventWriter events,
                                 com.sixbean.testpike.core.webelement.domain.service
                                         .WebElementStormService stormService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.pageKeys = pageKeys;
        this.lifecycle = lifecycle;
        this.events = events;
        this.stormService = stormService;
    }

    @Override
    public WebElementView create(WebElementInput input) {
        if (input.projectId() == null) {
            throw new ServiceException("请先选择项目再新建页面元素", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("元素名称不能为空，请使用团队看得懂的业务名（如「登录按钮」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<WebElementAnchorInput> anchors = validateAnchors(input.anchors());
        String pageKey = pageKeys.pageKey(input.pageUrl());
        Long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO tp_web_element (project_id, name, page_key, anchors, state)
                    VALUES (:pid, :name, :pk, CAST(:anchors AS jsonb), 0)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("pid", input.projectId())
                    .addValue("name", input.name().trim())
                    .addValue("pk", pageKey)
                    .addValue("anchors", writeAnchors(anchors)),
                    Long.class);
        } catch (DuplicateKeyException e) {
            throw samePageNameClash(pageKey, input.name().trim());
        }
        return findById(id);
    }

    @Override
    public WebElementView findById(long id) {
        List<WebElementView> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + "FROM tp_web_element WHERE id = :id",
                new MapSqlParameterSource("id", id), this::mapRow);
        if (rows.isEmpty()) {
            throw new ServiceException("该元素条目不存在（可能已被清理），请刷新后重试",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public List<WebElementView> listByProject(long projectId, String pageKey) {
        String sql = "SELECT " + SELECT_COLUMNS + "FROM tp_web_element WHERE project_id = :pid"
                + (pageKey == null || pageKey.isBlank() ? "" : " AND page_key = :pk")
                + " ORDER BY page_key, name";
        MapSqlParameterSource params = new MapSqlParameterSource("pid", projectId);
        if (pageKey != null && !pageKey.isBlank()) {
            params.addValue("pk", pageKey);
        }
        return jdbc.query(sql, params, this::mapRow);
    }

    @Override
    public List<WebElementPageView> pagesOfProject(long projectId) {
        LinkedHashMap<String, List<WebElementView>> byPage = new LinkedHashMap<>();
        for (WebElementView row : listByProject(projectId, null)) {
            byPage.computeIfAbsent(row.pageKey(), k -> new ArrayList<>()).add(row);
        }
        List<WebElementPageView> pages = new ArrayList<>();
        for (var entry : byPage.entrySet()) {
            List<WebElementView> elements = entry.getValue();
            int candidate = 0;
            int confirmed = 0;
            int stale = 0;
            int retired = 0;
            for (WebElementView element : elements) {
                switch (element.state()) {
                    case WebElementLifecycle.CANDIDATE -> candidate++;
                    case WebElementLifecycle.CONFIRMED -> confirmed++;
                    case WebElementLifecycle.STALE -> stale++;
                    case WebElementLifecycle.RETIRED -> retired++;
                    default -> { }
                }
            }
            pages.add(new WebElementPageView(entry.getKey(), List.copyOf(elements),
                    elements.size(), candidate, confirmed, stale, retired));
        }
        return pages;
    }

    @Override
    public WebElementView updateAnchors(long id, List<WebElementAnchorInput> anchors,
                                         int expectedRevision) {
        WebElementView row = requireRevision(id, expectedRevision);
        List<WebElementAnchorInput> validated = validateAnchors(anchors);
        int flipped = jdbc.update("""
                UPDATE tp_web_element
                SET anchors = CAST(:anchors AS jsonb), revision = revision + 1, updated_at = now()
                WHERE id = :id AND revision = :rev
                """, new MapSqlParameterSource()
                .addValue("id", row.id())
                .addValue("rev", expectedRevision)
                .addValue("anchors", writeAnchors(validated)));
        if (flipped == 0) {
            throw refusedConcurrently(row);
        }
        events.step(JOB_KIND, String.valueOf(row.id()), events.newRunToken(),
                "元素「" + row.name() + "」锚集更新为 " + validated.size() + " 条", null);
        return findById(row.id());
    }

    @Override
    public WebElementView updateVariants(long id,
                                         List<WebElementVariantInput> variants,
                                         int expectedRevision) {
        WebElementView row = requireRevision(id, expectedRevision);
        List<WebElementVariantInput> validated = validateVariants(variants);
        int flipped = jdbc.update("""
                UPDATE tp_web_element
                SET variants = CAST(:variants AS jsonb), revision = revision + 1, updated_at = now()
                WHERE id = :id AND revision = :rev
                """, new MapSqlParameterSource()
                .addValue("id", row.id())
                .addValue("rev", expectedRevision)
                .addValue("variants", writeVariants(validated)));
        if (flipped == 0) {
            throw refusedConcurrently(row);
        }
        events.step(JOB_KIND, String.valueOf(row.id()), events.newRunToken(),
                "元素「" + row.name() + "」视口变体更新为 " + validated.size() + " 档", null);
        return findById(row.id());
    }

    private List<WebElementVariantInput> validateVariants(List<WebElementVariantInput> variants) {
        List<WebElementVariantInput> validated = new ArrayList<>();
        if (variants == null) {
            return validated;
        }
        for (WebElementVariantInput variant : variants) {
            if (variant == null || variant.viewport() == null || variant.viewport().isBlank()) {
                throw new ServiceException("视口变体缺少档位名（mobile/tablet/desktop 之一），无法保存",
                        BizErrorCode.VALIDATION_ERROR);
            }
            validated.add(new WebElementVariantInput(
                    variant.viewport().trim().toLowerCase(),
                    validateAnchors(variant.anchors())));
        }
        return validated;
    }

    private String writeVariants(List<WebElementVariantInput> variants) {
        try {
            return mapper.writeValueAsString(variants);
        } catch (Exception e) {
            throw new ServiceException("视口变体无法保存（格式不受支持），请检查档位锚内容后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    @Override
    public WebElementView changeState(long id, int targetState, int expectedRevision) {
        WebElementView row = requireRevision(id, expectedRevision);
        lifecycle.requireAllowed(row.state(), targetState);
        int flipped = jdbc.update("""
                UPDATE tp_web_element
                SET state = :to, revision = revision + 1, updated_at = now()
                WHERE id = :id AND revision = :rev
                """, new MapSqlParameterSource()
                .addValue("id", row.id())
                .addValue("rev", expectedRevision)
                .addValue("to", targetState));
        if (flipped == 0) {
            throw refusedConcurrently(row);
        }
        events.step(JOB_KIND, String.valueOf(row.id()), events.newRunToken(),
                "元素「" + row.name() + "」状态 " + lifecycle.name(row.state())
                        + "→" + lifecycle.name(targetState), null);
        if (targetState == WebElementLifecycle.STALE) {
            stormService.recordStaleTransition(row.projectId(), row.pageKey(), row.id());
        }
        return findById(row.id());
    }

    /**
 * Rename door : {{ELEM:name}} references resolve by
 * name, so a mis-named row was previously unfixable by hand. Same CAS
 * token posture as the other write doors; the (project, page, name)
 * unique gate stays the single arbiter of same-page clashes; replaying
 * the current name is a no-op without revision churn (empty-save guard).
 */
    @Override
    public WebElementView rename(long id, String newName, int expectedRevision) {
        WebElementView row = requireRevision(id, expectedRevision);
        if (newName == null || newName.isBlank()) {
            throw new ServiceException("元素名称不能为空，请输入团队看得懂的业务名（如「登录按钮」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String trimmed = newName.trim();
        if (trimmed.equals(row.name())) {
            return row;
        }
        int flipped;
        try {
            flipped = jdbc.update("""
                    UPDATE tp_web_element
                    SET name = :name, revision = revision + 1, updated_at = now()
                    WHERE id = :id AND revision = :rev
                    """, new MapSqlParameterSource()
                    .addValue("id", row.id())
                    .addValue("rev", expectedRevision)
                    .addValue("name", trimmed));
        } catch (DuplicateKeyException e) {
            throw samePageNameClash(row.pageKey(), trimmed);
        }
        if (flipped == 0) {
            throw refusedConcurrently(row);
        }
        events.step(JOB_KIND, String.valueOf(row.id()), events.newRunToken(),
                "元素「" + row.name() + "」重命名为「" + trimmed + "」", null);
        return findById(row.id());
    }

    private ServiceException samePageNameClash(String pageKey, String name) {
        return new ServiceException("该页面下已存在名为「%s」的元素，请改名或直接编辑现有条目的锚（页面 %s）",
                BizErrorCode.RESOURCE_CONFLICT, name, pageKey);
    }

    /** Both write doors consume the optimistic lock with the shared 40901 posture. */
    private WebElementView requireRevision(long id, int expectedRevision) {
        WebElementView row = findById(id);
        if (row.revision() != expectedRevision) {
            throw refusedStale(row);
        }
        return row;
    }

    private ServiceException refusedStale(WebElementView row) {
        return new ServiceException("元素「%s」已被他人更新（当前版本 %s），请刷新后基于最新版本重试",
                BizErrorCode.VERSION_CONFLICT, row.name(), row.revision());
    }

    /** Same 40901 wording when the row slips between the token check and the CAS. */
    private ServiceException refusedConcurrently(WebElementView row) {
        return refusedStale(row);
    }

    private List<WebElementAnchorInput> validateAnchors(List<WebElementAnchorInput> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            throw new ServiceException("请至少提供一个定位锚（如 testid / css / xpath），空锚条目无法被执行器定位",
                    BizErrorCode.VALIDATION_ERROR);
        }
        for (WebElementAnchorInput anchor : anchors) {
            if (anchor.type() == null || !ANCHOR_TYPES.contains(anchor.type().toLowerCase())) {
                throw new ServiceException("锚类型「%s」不在支持范围，允许：css、xpath、text、placeholder、alt、testid、rel、role、label",
                        BizErrorCode.VALIDATION_ERROR, anchor.type());
            }
            if (anchor.value() == null || anchor.value().isBlank()) {
                throw new ServiceException("锚表达式不能为空（类型 " + anchor.type() + "）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (anchor.strength() == null || anchor.strength() < 0 || anchor.strength() > 1) {
                throw new ServiceException("锚强度必须是 0 到 1 之间的数字（当前 %s），它决定多锚尝试的排序权重",
                        BizErrorCode.VALIDATION_ERROR, anchor.strength());
            }
        }
        return anchors;
    }

    @Override
    public List<WebElementView> findForDispatch(long projectId, java.util.Collection<String> names,
                                                boolean allowCandidate) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        org.springframework.jdbc.core.namedparam.MapSqlParameterSource params =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("names", names)
                        // state domain: 0=candidate 1=confirmed 2=stale 3=retired
                        .addValue("states", allowCandidate ? List.of(0, 1) : List.of(1));
        return jdbc.query("""
                SELECT id, project_id, name, page_key, anchors, variants, state, confidence,
                       hit_count, miss_count, revision, created_at, updated_at
                  FROM tp_web_element
                 WHERE project_id = :projectId AND name IN (:names) AND state IN (:states)
                 ORDER BY page_key, name
                """, params, this::mapRow);
    }

    @Override
    public List<WebElementView> promptSummary(long projectId, String pageKey, int limit) {
        int clamped = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT " + SELECT_COLUMNS + "FROM tp_web_element"
                + " WHERE project_id = :pid AND state = 1"
                + (pageKey == null || pageKey.isBlank() ? "" : " AND page_key = :pk")
                + " ORDER BY confidence DESC, id ASC LIMIT :lim";
        MapSqlParameterSource params = new MapSqlParameterSource("pid", projectId)
                .addValue("lim", clamped);
        if (pageKey != null && !pageKey.isBlank()) {
            params.addValue("pk", pageKey);
        }
        return jdbc.query(sql, params, this::mapRow);
    }

    private String writeAnchors(List<WebElementAnchorInput> anchors) {
        try {
            return mapper.writeValueAsString(anchors);
        } catch (Exception e) {
            throw new ServiceException("锚集无法保存（格式不受支持），请检查锚内容后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private List<WebElementAnchorInput> readAnchors(String json) {
        List<WebElementAnchorInput> anchors = new ArrayList<>();
        try {
            JsonNode array = mapper.readTree(json);
            for (JsonNode node : array) {
                anchors.add(new WebElementAnchorInput(
                        node.path("type").asText(null),
                        node.path("value").asText(null),
                        node.hasNonNull("strength") ? node.get("strength").asDouble() : null,
                        node.path("note").asText(null)));
            }
        } catch (Exception e) {
            // A malformed stored array must not black-hole the row read.
        }
        return anchors;
    }

    /**
     * Per-viewport-class variant sets (design §8.2, column added by V556).
     * Rows stored before WE S4 carry the column default {@code '[]'}, so every
     * legacy read is byte-equal to the pre-variants shape; a malformed item
     * (missing viewport or anchors array) is skipped item-wise rather than
     * black-holing the whole row (same posture as readAnchors).
     */
    private List<WebElementVariantInput> readVariants(String json) {
        List<WebElementVariantInput> variants = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return variants;
        }
        try {
            for (JsonNode node : mapper.readTree(json)) {
                String viewport = node.path("viewport").asText(null);
                JsonNode anchors = node.path("anchors");
                if (viewport == null || viewport.isBlank() || !anchors.isArray()) {
                    continue;
                }
                variants.add(new WebElementVariantInput(viewport, readAnchors(anchors.toString())));
            }
        } catch (Exception malformed) {
            // Damaged jsonb degrades to "no variants" — the top-level anchor
            // set still dispatches (fail-soft, never a claim-round crash).
        }
        return variants;
    }

    /** Row shape shared by every read door. */
    private WebElementView mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WebElementView(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
                rs.getString("page_key"),
                readAnchors(rs.getString("anchors")),
                readVariants(rs.getString("variants")),
                rs.getInt("state"), rs.getDouble("confidence"),
                rs.getLong("hit_count"), rs.getLong("miss_count"),
                rs.getInt("revision"),
                toLocal(rs.getTimestamp("created_at")), toLocal(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocal(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}

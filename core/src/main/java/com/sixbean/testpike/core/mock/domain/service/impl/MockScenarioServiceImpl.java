package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInfo;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioPageView;
import com.sixbean.testpike.common.mock.MockScenarioRules;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.core.mock.domain.service.MockScenarioService;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockScenarioDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Scenario declaration service implementation. The machine shape is validated
 * by the shared {@link MockScenarioRules} kernel before the row lands (same
 * single-point discipline as stubs — a broken machine fails the editor, never
 * the traffic plane). (space_id, name) uniqueness surfaces as a plain-word
 * conflict naming the occupied scenario instead of a database 500.
 */
@Service
public class MockScenarioServiceImpl implements MockScenarioService {

    private static final String SCENARIO_COLUMNS = """
            id, space_id, name, definition_json::text AS definitionJson,
            created_at, updated_at
            """;

    private static final BeanPropertyRowMapper<MockScenarioDO> MAPPER =
            new BeanPropertyRowMapper<>(MockScenarioDO.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final MockScenarioRules rules;

    public MockScenarioServiceImpl(NamedParameterJdbcTemplate jdbc, MockScenarioRules rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    @Override
    @Transactional
    public long create(long spaceId, MockScenarioInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new ServiceException("场景名称必填", BizErrorCode.VALIDATION_ERROR);
        }
        String name = input.name().trim();
        if (name.length() > 80) {
            throw new ServiceException("场景名超长（上限 80 字符）", BizErrorCode.VALIDATION_ERROR);
        }
        rules.declaredStates(input.definitionJson());
        requireSpace(spaceId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("name", name)
                .addValue("definition", input.definitionJson().trim());
        try {
            return jdbc.queryForObject("""
                    INSERT INTO tp_mock_scenario (space_id, name, definition_json)
                    VALUES (:spaceId, :name, CAST(:definition AS jsonb))
                    RETURNING id
                    """, params, Long.class);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("该空间下场景「" + name + "」已存在——同一空间内场景名唯一",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    public MockScenarioInfo get(long id) {
        List<MockScenarioDO> rows = jdbc.query("SELECT " + SCENARIO_COLUMNS
                        + " FROM tp_mock_scenario WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("场景不存在或已被删除", BizErrorCode.VALIDATION_ERROR);
        }
        return toInfo(rows.get(0));
    }

    @Override
    public List<MockScenarioInfo> listBySpace(long spaceId) {
        return jdbc.query("SELECT " + SCENARIO_COLUMNS
                        + " FROM tp_mock_scenario WHERE space_id = :sid ORDER BY name",
                new MapSqlParameterSource("sid", spaceId), MAPPER)
                .stream().map(this::toInfo).toList();
    }

    @Override
    public MockScenarioPageView listBySpacePaged(long spaceId, int page, int pageSize) {
        // Historical callers keep the name-first window untouched.
        return listBySpacePaged(spaceId, page, pageSize, null, null);
    }

    /**
     * Column keys the scenario list may sort by -> SQL expressions (physical
     * columns, the declared lane, and the counted states/transitions facets —
     * the numbers the page renders as columns). Values are never user input:
     * off-list/injection-shaped keys are refused by the shared fail-closed
     * gate before any SQL is built.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "created_at", "created_at",
            "updated_at", "updated_at",
            "scope", "definition_json->>'scope'",
            "states_count", "CASE WHEN jsonb_typeof(definition_json->'states') = 'array'"
                    + " THEN jsonb_array_length(definition_json->'states') ELSE 0 END",
            "transitions_count", "CASE WHEN jsonb_typeof(definition_json->'transitions') = 'array'"
                    + " THEN jsonb_array_length(definition_json->'transitions') ELSE 0 END");

    @Override
    public MockScenarioPageView listBySpacePaged(long spaceId, int page, int pageSize,
                                                 String sortBy, String sortDir) {
        requireSpace(spaceId);
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource("sid", spaceId);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_scenario WHERE space_id = :sid",
                params, Long.class);
        List<MockScenarioInfo> rows = jdbc.query("SELECT " + SCENARIO_COLUMNS
                        + " FROM tp_mock_scenario WHERE space_id = :sid"
                        + orderClause(sortBy, sortDir) + " LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()),
                MAPPER)
                .stream().map(this::toInfo).toList();
        return new MockScenarioPageView(rows, total == null ? 0L : total,
                window.page(), window.size());
    }

    /**
     * ORDER BY builder: a null sort key keeps the historical name order (the
     * editor picklists and the docs surface read positions off it); a
     * whitelisted key orders by that expression with the row id as
     * tie-breaker so paging stays deterministic. The key itself passes the
     * shared fail-closed gate — only map VALUES (physical columns/computed
     * facet expressions) ever reach the SQL string.
     */
    private static String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "Mock场景列表");
        if (column == null) {
            return " ORDER BY name";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    private void requireSpace(long spaceId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tp_mock_space WHERE id = :id",
                new MapSqlParameterSource("id", spaceId), Integer.class);
        if (count == null || count == 0) {
            throw new ServiceException("所属 Mock 空间不存在，请先创建空间再配置场景",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private MockScenarioInfo toInfo(MockScenarioDO row) {
        return new MockScenarioInfo(row.getId(), row.getSpaceId(), row.getName(),
                row.getDefinitionJson(), row.getCreatedAt(), row.getUpdatedAt());
    }
}

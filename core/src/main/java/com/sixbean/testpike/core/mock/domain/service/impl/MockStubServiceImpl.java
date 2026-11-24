package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.component.dedup.DedupGate;
import com.sixbean.testpike.core.common.component.dedup.DedupVerdict;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubPageView;
import com.sixbean.testpike.core.mock.domain.component.MockRuleRefusals;
import com.sixbean.testpike.core.mock.domain.component.MockRuleValidator;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.common.mock.MockScenarioRules;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockStubDO;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Mock stub service implementation — writes land on tp_mock_stub, the single
 * rule source (red line: UI, snapshot loader and executor share these rows, a
 * second rule table would fork the truth).
 * <p>Validation is the same MockRuleValidator single point the space segment
 * uses; the space FK is checked here so a rule can never be filed under a
 * space that does not exist (the FK constraint would only speak SQL).
 */
@Service
public class MockStubServiceImpl implements MockStubService {

    private static final String STUB_COLUMNS = """
            id, space_id, name, priority, method, path_match_type, path_pattern,
            matchers_json::text AS matchers_json, response_json::text AS response_json,
            scenario_name, required_state, new_state, source, source_ref, enabled,
            version, created_at, updated_at
            """;

    private static final BeanPropertyRowMapper<MockStubDO> MAPPER =
            new BeanPropertyRowMapper<>(MockStubDO.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final MockRuleValidator validator;
    private final MockScenarioRules scenarioRules;
    private final DedupGate dedupGate;
    private final com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
            objectChangedEvents;

    public MockStubServiceImpl(NamedParameterJdbcTemplate jdbc, MockRuleValidator validator,
                               MockScenarioRules scenarioRules, DedupGate dedupGate,
                               com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
                                       objectChangedEvents) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.scenarioRules = scenarioRules;
        this.dedupGate = dedupGate;
        this.objectChangedEvents = objectChangedEvents;
    }

    @Override
    @Transactional
    public long create(long spaceId, MockStubInput input) {
        validator.validateStub(input);
        requireSpace(spaceId);
        // Duplicate-rule gate (design A7 business key): same method + path
        // pattern + canonical matcher payload inside this space is the very
        // same routing rule — refuse and name it. A different matcher payload
        // or method/path stays a legitimate sibling rule.
        String matchers = input.matchersJson() == null || input.matchersJson().isBlank()
                ? "[]" : input.matchersJson().trim();
        DedupVerdict verdict = dedupGate.evaluateMockStub(spaceId,
                input.method(), input.pathPattern(), matchers);
        if (verdict.blocksCreate()) {
            throw new ServiceException(String.format(
                    "本空间已存在相同路由规则（id=%d，名称《%s》），重复创建被检出拦截；"
                            + "如需调整请编辑既有条目，如确属不同规则请修改方法、路径或匹配条件。"
                            + "该拦截可在「系统设置 → 系统配置」将 ai.dedup.enabled 设为关闭（修改即时生效）",
                    verdict.matched().id(), dedupGate.stubRuleName(verdict.matched().id())),
                    BizErrorCode.VALIDATION_ERROR);
        }
        String scenarioName = blankToNull(input.scenarioName());
        String requiredState = blankToNull(input.requiredState());
        String newState = blankToNull(input.newState());
        requireScenarioStates(spaceId, scenarioName, requiredState, newState);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("name", input.name().trim())
                .addValue("priority", input.priority() == null ? 100 : input.priority())
                .addValue("method", input.method().trim().toUpperCase())
                .addValue("pathMatchType", input.pathMatchType() == null ? "exact" : input.pathMatchType())
                .addValue("pathPattern", input.pathPattern().trim())
                // An omitted payload lands the column shell default ([], {}), never
                // an explicit NULL — the columns are NOT NULL by design.
                .addValue("matchers", blankToNull(input.matchersJson()) == null
                        ? "[]" : input.matchersJson().trim())
                .addValue("response", blankToNull(input.responseJson()) == null
                        ? "{}" : input.responseJson().trim())
                .addValue("scenarioName", scenarioName)
                .addValue("requiredState", requiredState)
                .addValue("newState", newState)
                .addValue("source", input.source() == null ? "manual" : input.source())
                .addValue("sourceRef", blankToNull(input.sourceRef()))
                .addValue("enabled", input.enabled() == null || input.enabled());
        long id = jdbc.queryForObject("""
                INSERT INTO tp_mock_stub
                    (space_id, name, priority, method, path_match_type, path_pattern,
                     matchers_json, response_json, scenario_name, required_state, new_state,
                     source, source_ref, enabled)
                VALUES
                    (:spaceId, :name, :priority, :method, :pathMatchType, :pathPattern,
                     CAST(:matchers AS jsonb), CAST(:response AS jsonb), :scenarioName,
                     :requiredState, :newState, :source, :sourceRef, :enabled)
                RETURNING id
                """, params, Long.class);
        // A brand-new rule joins a scenario flow the same way an edit changes
        // one: announce it with the row's initial version (column default 1).
        publishStubChanged(id, spaceId, 1);
        return id;
    }

    /**
     * Edit one rule through the SAME save-chain gates as create (validation
     * single point, scenario declaration kernel — no second copy). The rule
     * stays in its own space: moving a live endpoint between routing handles
     * would silently break every existing caller URL, so space_id is never a
     * mutable column here.
     * <p>Concurrency: the caller echoes the row version it read; a stale echo
     * means someone else saved in between (double-opened editor, contract
     * import racing the form) and is refused loudly with the plain-word
     * conflict, never silently overwritten. The snapshot pin of the owning
     * space moves with the row (pinOf digests updatedAt + payload), so the
     * traffic plane picks the edit up on the next poll — an edit of a live
     * rule takes effect within one rule-load interval.
     */
    @Override
    @Audit(objectType = "mock_stub", action = "mock_stub_update", objectId = "#stubId",
            before = "{expectedVersion: #input.expectedVersion()}", after = "#result")
    @Transactional
    public MockStubInfo update(long stubId, MockStubInput input) {
        MockStubDO current = findStub(stubId);
        validator.validateStub(input);
        long spaceId = current.getSpaceId();
        String scenarioName = blankToNull(input.scenarioName());
        String requiredState = blankToNull(input.requiredState());
        String newState = blankToNull(input.newState());
        requireScenarioStates(spaceId, scenarioName, requiredState, newState);
        // Provenance columns (source, source_ref) are birth facts: the UPDATE
        // below never touches them, so an editor can never forge the origin
        // badge or break the §12a return-to-source door.
        // Editable spectrum note for callers: every source (manual, openapi,
        // record, nl-ai, case-flip) is editable here. Re-import and re-promote
        // skip rules that already cover the same method+path (idempotent by
        // design), so an edited derived rule is never silently re-overwritten.
        int expected = input.expectedVersion() == null
                ? (current.getVersion() == null ? 1 : current.getVersion())
                : input.expectedVersion();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", stubId)
                .addValue("expected", expected)
                .addValue("name", input.name().trim())
                .addValue("priority", input.priority() == null ? 100 : input.priority())
                .addValue("method", input.method().trim().toUpperCase())
                .addValue("pathMatchType", input.pathMatchType() == null ? "exact" : input.pathMatchType())
                .addValue("pathPattern", input.pathPattern().trim())
                .addValue("matchers", blankToNull(input.matchersJson()) == null
                        ? "[]" : input.matchersJson().trim())
                .addValue("response", blankToNull(input.responseJson()) == null
                        ? "{}" : input.responseJson().trim())
                .addValue("scenarioName", scenarioName)
                .addValue("requiredState", requiredState)
                .addValue("newState", newState)
                .addValue("enabled", input.enabled() == null || input.enabled());
        int moved = jdbc.update("""
                UPDATE tp_mock_stub
                   SET name = :name, priority = :priority, method = :method,
                       path_match_type = :pathMatchType, path_pattern = :pathPattern,
                       matchers_json = CAST(:matchers AS jsonb),
                       response_json = CAST(:response AS jsonb),
                       scenario_name = :scenarioName, required_state = :requiredState,
                       new_state = :newState, enabled = :enabled,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected
                """, params);
        if (moved == 0) {
            throw new ServiceException(MockRuleRefusals.stubVersionStale(stubId, expected),
                    BizErrorCode.VERSION_CONFLICT);
        }
        // The saved row now carries version expected + 1 — announce exactly
        // that, so the impact ledger's version guard re-arms downstream edges
        // instead of dropping the event as stale.
        publishStubChanged(stubId, spaceId, expected + 1);
        return toInfo(findStub(stubId));
    }

    /**
     * Announce one stub save on the linkage bus. The stub row carries no
     * project id (project ownership lives on the space), so the owning
     * project is resolved through tp_mock_space per save — one indexed read,
     * never cached (R57: no unbounded map for a two-row lookup).
     * changedFields stays null: the mock edges arm on any saved change, the
     * ledger trigger is scenario-name based, not field based. The publisher
     * defers the send to afterCommit, so a rolled-back save announces nothing.
     */
    private void publishStubChanged(long stubId, long spaceId, int objectVersion) {
        Long projectId = jdbc.queryForList(
                        "SELECT project_id FROM tp_mock_space WHERE id = :spaceId",
                        new MapSqlParameterSource("spaceId", spaceId))
                .stream().findFirst()
                .map(r -> ((Number) r.get("project_id")).longValue())
                .orElse(null);
        objectChangedEvents.publish(
                new com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage(
                        com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType
                                .MOCK.name(),
                        stubId, projectId, null, objectVersion,
                        null, System.currentTimeMillis()));
    }

    /**
     * Remove one rule. Consequences are honest and bounded: the owning space
     * content pin moves (pinOf digests the rule set), so the traffic plane
     * stops answering this endpoint within one rule-load poll; the hit ledger
     * keeps its rows (stub_id carries no FK by design — traffic accounting
     * history survives rule churn, same posture as deleteDraft keeping the
     * ledger). The caller-visible impact count (recent hits) is computed by
     * the admin plane before asking, never guessed here.
     */
    @Override
    @Audit(objectType = "mock_stub", action = "mock_stub_delete", objectId = "#stubId")
    @Transactional
    public void delete(long stubId) {
        findStub(stubId); // ghost id refused in plain words before anything is removed
        jdbc.update("DELETE FROM tp_mock_stub WHERE id = :id",
                new MapSqlParameterSource("id", stubId));
    }

    /**
     * Matched-serve count of one rule in the last {@code days} days (impact
     * preview for the delete confirmation door). Reads the hit ledger by the
     * (stub_id, hit_at DESC) index V240 added for exactly this query; a ghost
     * rule is refused in plain words like every other read door.
     */
    @Override
    public long recentHitCount(long stubId, int days) {
        findStub(stubId);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tp_mock_hit_log
                 WHERE stub_id = :sid AND matched = TRUE
                   AND hit_at >= now() - make_interval(days => :days)
                """, new MapSqlParameterSource("sid", stubId).addValue("days", days),
                Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public MockStubInfo get(long id) {
        return toInfo(findStub(id));
    }

    /** Read one rule row; a ghost id is refused in plain words (never null). */
    private MockStubDO findStub(long stubId) {
        List<MockStubDO> rows = jdbc.query("SELECT " + STUB_COLUMNS
                + " FROM tp_mock_stub WHERE id = :id",
                new MapSqlParameterSource("id", stubId), MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException(MockRuleRefusals.stubMissing(stubId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    @Override
    public List<MockStubInfo> listBySpace(long spaceId) {
        return jdbc.query("SELECT " + STUB_COLUMNS
                        + " FROM tp_mock_stub WHERE space_id = :sid ORDER BY priority, id",
                new MapSqlParameterSource("sid", spaceId), MAPPER)
                .stream().map(this::toInfo).toList();
    }

    @Override
    public MockStubPageView listBySpacePaged(long spaceId, int page, int pageSize) {
        requireSpace(spaceId);
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource("sid", spaceId);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_stub WHERE space_id = :sid",
                params, Long.class);
        List<MockStubInfo> rows = jdbc.query("SELECT " + STUB_COLUMNS
                        + " FROM tp_mock_stub WHERE space_id = :sid"
                        + " ORDER BY priority, id LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()),
                MAPPER)
                .stream().map(this::toInfo).toList();
        return new MockStubPageView(rows, total == null ? 0L : total,
                window.page(), window.size());
    }

    /**
     * Scenario/state declaration gate shared by create and update (one kernel,
     * red line 6): no orphan state columns without a machine, and both states
     * must be declared by the bound scenario — the same verdicts the traffic
     * plane relies on, frozen at save time so a bad row can never be born or
     * edited into existing.
     */
    private void requireScenarioStates(long spaceId, String scenarioName,
                                       String requiredState, String newState) {
        if (scenarioName == null) {
            // no orphan state columns: a state without a machine is meaningless
            // and would silently never fire on the traffic plane
            if (requiredState != null || newState != null) {
                throw new ServiceException(MockRuleRefusals.stateColumnsWithoutScenario(),
                        BizErrorCode.VALIDATION_ERROR);
            }
            return;
        }
        Set<String> declared = declaredStatesOf(spaceId, scenarioName);
        scenarioRules.requireDeclared(declared, requiredState, scenarioName, "触发状态");
        scenarioRules.requireDeclared(declared, newState, scenarioName, "推进状态");
    }

    private void requireSpace(long spaceId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tp_mock_space WHERE id = :id",
                new MapSqlParameterSource("id", spaceId), Integer.class);
        if (count == null || count == 0) {
            throw new ServiceException(MockRuleRefusals.stubSpaceMissing(spaceId),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * The declared state set of one scenario inside this space. An unknown name
     * is refused here (naming the machine) instead of at traffic time — a stub
     * bound to a ghost scenario could never fire and the operator deserves the
     * plain-word answer at save time.
     */
    private Set<String> declaredStatesOf(long spaceId, String scenarioName) {
        List<String> definitions = jdbc.queryForList(
                "SELECT definition_json::text FROM tp_mock_scenario"
                        + " WHERE space_id = :sid AND name = :name",
                new MapSqlParameterSource("sid", spaceId).addValue("name", scenarioName),
                String.class);
        if (definitions.isEmpty()) {
            throw new ServiceException(MockRuleRefusals.scenarioGhost(scenarioName),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return scenarioRules.declaredStates(definitions.get(0));
    }

    private MockStubInfo toInfo(MockStubDO row) {
        return new MockStubInfo(row.getId(), row.getSpaceId(), row.getName(), row.getPriority(),
                row.getMethod(), row.getPathMatchType(), row.getPathPattern(), row.getMatchersJson(),
                row.getResponseJson(), row.getScenarioName(), row.getRequiredState(), row.getNewState(),
                row.getSource(), row.getSourceRef(), row.getEnabled(), row.getVersion(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

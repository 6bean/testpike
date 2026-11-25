package com.sixbean.testpike.core.mock.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.mock.api.dto.MockStubDraftAdvice;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.SpecImportVerdict;
import com.sixbean.testpike.core.mock.domain.component.MockRuleKeys;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.mock.domain.service.SpecImportVerdictService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic pre-adjudication implementation (MJ P3 rule floor).
 * <p>Conflict posture (adjudication D9): an endpoint the space already serves
 * gets {@code avoid + skip} — the import is idempotent and silently
 * double-filing would fake a second coverage of the same path. Fresh endpoints
 * are {@code recommend} with the draft's own name as the business one-liner.
 * <p>Impact reading is real numbers only: 7-day matched hits of the conflicting
 * rule come from tp_mock_hit_log (the same ledger the serving inventory
 * counts); fresh endpoints honestly report zeros. Realism is graded from the
 * stored sample body shape (static echo / thin echo / expanded schema), so the
 * advice tells the operator whether the response data was truly filled in.
 * <p>Confidence tiers are deterministic facts, never a guessed percentage:
 * a conflict reads ground truth (90), an expanded schema body is dependable
 * advice (75), a thin echo (65) and a static placeholder (55) say so.
 */
@Service
public class SpecImportVerdictServiceImpl implements SpecImportVerdictService {

    /** 7-day window of the impact reading, aligned with the serving inventory. */
    static final int IMPACT_WINDOW_DAYS = 7;

    /** Hot window the wizard waits for the AI backfill round before degrading. */
    public static final String KEY_DEADLINE_SECONDS = "mock.spec-import.verdict-deadline-seconds";
    static final int DEFAULT_DEADLINE_SECONDS = 20;
    static final int MIN_DEADLINE_SECONDS = 1;
    static final int MAX_DEADLINE_SECONDS = 600;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockStubService stubService;
    private final ConfigService configService;
    private final JdbcTemplate jdbc;

    public SpecImportVerdictServiceImpl(MockStubService stubService, ConfigService configService,
                                        JdbcTemplate jdbc) {
        this.stubService = stubService;
        this.configService = configService;
        this.jdbc = jdbc;
    }

    @Override
    public List<SpecImportVerdict> judge(long specId, long spaceId, List<MockStubDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        Map<String, MockStubInfo> served = new HashMap<>();
        for (MockStubInfo stub : stubService.listBySpace(spaceId)) {
            served.put(MockRuleKeys.of(stub.method(), stub.pathPattern()), stub);
        }
        List<SpecImportVerdict> rows = new ArrayList<>(drafts.size());
        for (MockStubDraft draft : drafts) {
            rows.add(buildOne(draft, served.get(
                    MockRuleKeys.of(draft.method(), draft.pathPattern()))));
        }
        for (SpecImportVerdict row : rows) {
            store(specId, spaceId, row);
        }
        return rows;
    }

    @Override
    public List<SpecImportVerdict> listVerdicts(long specId, long spaceId) {
        return jdbc.query("""
                SELECT endpoint_key, verdict, reason, conflict_action,
                       CAST(impact_json ->> 'conflictStubId' AS BIGINT) AS conflict_stub_id,
                       impact_json, realism, confidence, origin, created_at
                  FROM tp_mock_spec_import_verdict
                 WHERE spec_id = ? AND space_id = ?
                 ORDER BY endpoint_key
                """, (rs, n) -> new SpecImportVerdict(
                        rs.getString("endpoint_key"),
                        rs.getString("verdict"),
                        rs.getString("reason"),
                        rs.getString("conflict_action"),
                        (Long) rs.getObject("conflict_stub_id"),
                        rs.getString("impact_json"),
                        rs.getInt("realism"),
                        rs.getInt("confidence"),
                        rs.getString("origin"),
                        rs.getTimestamp("created_at").getTime()),
                specId, spaceId);
    }

    @Override
    public List<MockStubDraftAdvice> advise(long specId, long spaceId, List<MockStubDraft> drafts) {
        judge(specId, spaceId, drafts);
        Map<String, SpecImportVerdict> stored = new LinkedHashMap<>();
        for (SpecImportVerdict row : listVerdicts(specId, spaceId)) {
            stored.put(row.endpointKey(), row);
        }
        long deadlineMillis = resolveDeadlineSeconds() * 1000L;
        long now = System.currentTimeMillis();
        List<MockStubDraftAdvice> rows = new ArrayList<>(drafts.size());
        for (MockStubDraft draft : drafts) {
            SpecImportVerdict advice = stored.get(draft.endpointKey());
            if (advice == null) {
                rows.add(MockStubDraftAdvice.withoutAdvice(List.of(draft)).get(0));
                continue;
            }
            rows.add(new MockStubDraftAdvice(draft.endpointKey(), draft.name(), draft.method(),
                    draft.pathMatchType(), draft.pathPattern(), draft.matchersJson(),
                    draft.responseJson(), draft.source(), draft.sourceRef(),
                    advice.verdict(), advice.reason(), advice.conflictAction(),
                    advice.conflictStubId(), advice.impactJson(),
                    advice.realism(), advice.confidence(), advice.origin(),
                    stateOf(advice, now, deadlineMillis),
                    SpecImportVerdict.VERDICT_RECOMMEND.equals(advice.verdict())));
        }
        return rows;
    }

    @Override
    public void applyAiRefinement(long specId, long spaceId, List<SpecImportVerdict> refined) {
        if (refined == null || refined.isEmpty()) {
            return;
        }
        for (SpecImportVerdict row : refined) {
            if (row == null || row.endpointKey() == null || row.endpointKey().isBlank()) {
                continue;
            }
            // intake validates, never improvises: an unusable verdict is skipped
            if (!SpecImportVerdict.VERDICT_RECOMMEND.equals(row.verdict())
                    && !SpecImportVerdict.VERDICT_AVOID.equals(row.verdict())) {
                continue;
            }
            // only rows the deterministic floor judged may be refined (no twins)
            jdbc.update("""
                    UPDATE tp_mock_spec_import_verdict
                       SET verdict = ?, reason = ?, conflict_action = ?,
                           impact_json = COALESCE(?::jsonb, impact_json),
                           realism = ?, confidence = ?, origin = 'ai', updated_at = now()
                     WHERE spec_id = ? AND space_id = ? AND endpoint_key = ?
                    """, row.verdict(), clampOrKeep(row.reason()), row.conflictAction(),
                    row.impactJson(), row.realism(), row.confidence(),
                    specId, spaceId, row.endpointKey());
        }
    }

    /** Fresh backfill window from the hot key, clamped; garbage falls back. */
    int resolveDeadlineSeconds() {
        String raw = configService.getValue(KEY_DEADLINE_SECONDS,
                String.valueOf(DEFAULT_DEADLINE_SECONDS));
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.min(MAX_DEADLINE_SECONDS, Math.max(MIN_DEADLINE_SECONDS, parsed));
        } catch (Exception e) {
            return DEFAULT_DEADLINE_SECONDS;
        }
    }

    private String stateOf(SpecImportVerdict row, long now, long deadlineMillis) {
        if (SpecImportVerdict.ORIGIN_AI.equals(row.origin())) {
            return MockStubDraftAdvice.STATE_REFINED;
        }
        return now - row.storedAtMillis() < deadlineMillis
                ? MockStubDraftAdvice.STATE_PENDING
                : MockStubDraftAdvice.STATE_DEGRADED;
    }

    private SpecImportVerdict buildOne(MockStubDraft draft, MockStubInfo conflict) {
        int realism = realismOf(draft.responseJson());
        if (conflict != null) {
            long hits = hits7d(conflict.id());
            String impact = impactJson(conflict.id(), conflict.name(), hits);
            String reason = "仿真空间内已有同接口规则「" + conflict.name()
                    + "」，重复批量启用会被跳过；如需更换返回数据请直接编辑该条已有规则。";
            return new SpecImportVerdict(draft.endpointKey(), SpecImportVerdict.VERDICT_AVOID,
                    clamp(reason), SpecImportVerdict.ACTION_SKIP, conflict.id(), impact,
                    realism, 90, SpecImportVerdict.ORIGIN_RULE, System.currentTimeMillis());
        }
        String reason = "契约端点 " + draft.method() + " " + draft.pathPattern()
                + "（" + draft.name() + "）在本空间尚无规则，启用后即可用于联调与用例执行。";
        return new SpecImportVerdict(draft.endpointKey(), SpecImportVerdict.VERDICT_RECOMMEND,
                clamp(reason), null, null, impactJson(null, null, 0),
                realism, confidenceFor(realism), SpecImportVerdict.ORIGIN_RULE,
                System.currentTimeMillis());
    }

    /**
     * Realism grade of a sample body: 20 = only the static {code,message}
     * placeholder (nothing was actually initialized), 45 = a thin flat echo,
     * 80 = a real expanded schema (nested fields or a key-rich object).
     */
    private int realismOf(String responseJson) {
        try {
            JsonNode body = JSON.readTree(responseJson == null ? "{}" : responseJson).path("body");
            if (body.isTextual()) {
                body = JSON.readTree(body.asText());
            }
            if (body.isObject()) {
                List<String> names = new ArrayList<>();
                int total = countKeys(body, 0, names);
                if (total <= 2 && names.contains("code") && names.contains("message")) {
                    return 20;
                }
                if (total <= 3 && depth(body) <= 1) {
                    return 45;
                }
                return 80;
            }
            return body.isArray() || !body.isMissingNode() && !body.isNull() ? 45 : 20;
        } catch (Exception e) {
            // an unparsable response document cannot prove data quality
            return 20;
        }
    }

    private int countKeys(JsonNode node, int acc, List<String> names) {
        if (node.isObject()) {
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
                acc++;
            }
            for (JsonNode child : node) {
                acc = countKeys(child, acc, names);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                acc = countKeys(child, acc, names);
            }
        }
        return acc;
    }

    private int depth(JsonNode node) {
        int max = 0;
        for (JsonNode child : node) {
            max = Math.max(max, 1 + depth(child));
        }
        return max;
    }

    private int confidenceFor(int realism) {
        if (realism >= 80) {
            return 75;
        }
        return realism >= 45 ? 65 : 55;
    }

    private long hits7d(long stubId) {
        Long hits = jdbc.queryForObject("""
                SELECT count(*) FROM tp_mock_hit_log
                 WHERE stub_id = ? AND matched = TRUE
                   AND hit_at >= now() - make_interval(days => ?)
                """, Long.class, stubId, IMPACT_WINDOW_DAYS);
        return hits == null ? 0 : hits;
    }

    private String impactJson(Long conflictStubId, String conflictStubName, long hits7d) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("conflictStubId", conflictStubId);
        impact.put("conflictStubName", conflictStubName);
        impact.put("hits7d", hits7d);
        try {
            return JSON.writeValueAsString(impact);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * One row per (spec, space, endpoint): update in place, never twins.
     * A refined row keeps its AI advice through re-previews (the backfill
     * round owns refinement freshness); impact readings still refresh every
     * round so the 7-day hit account never goes stale.
     */
    private void store(long specId, long spaceId, SpecImportVerdict row) {
        jdbc.update("""
                INSERT INTO tp_mock_spec_import_verdict
                      (spec_id, space_id, endpoint_key, verdict, reason, conflict_action,
                       impact_json, realism, confidence, origin, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now(), now())
                ON CONFLICT (spec_id, space_id, endpoint_key) DO UPDATE SET
                       verdict = CASE WHEN tp_mock_spec_import_verdict.origin = 'ai'
                                      THEN tp_mock_spec_import_verdict.verdict
                                      ELSE EXCLUDED.verdict END,
                       reason = CASE WHEN tp_mock_spec_import_verdict.origin = 'ai'
                                     THEN tp_mock_spec_import_verdict.reason
                                     ELSE EXCLUDED.reason END,
                       conflict_action = CASE WHEN tp_mock_spec_import_verdict.origin = 'ai'
                                              THEN tp_mock_spec_import_verdict.conflict_action
                                              ELSE EXCLUDED.conflict_action END,
                       impact_json = EXCLUDED.impact_json,
                       realism = CASE WHEN tp_mock_spec_import_verdict.origin = 'ai'
                                      THEN tp_mock_spec_import_verdict.realism
                                      ELSE EXCLUDED.realism END,
                       confidence = CASE WHEN tp_mock_spec_import_verdict.origin = 'ai'
                                         THEN tp_mock_spec_import_verdict.confidence
                                         ELSE EXCLUDED.confidence END,
                       origin = CASE WHEN tp_mock_spec_import_verdict.origin = 'ai'
                                     THEN tp_mock_spec_import_verdict.origin
                                     ELSE EXCLUDED.origin END,
                       updated_at = now()
                """, specId, spaceId, row.endpointKey(), row.verdict(), row.reason(),
                row.conflictAction(), row.impactJson(), row.realism(), row.confidence(),
                row.origin());
    }

    private String clamp(String reason) {
        return reason.length() <= 500 ? reason : reason.substring(0, 497) + "...";
    }

    private String clampOrKeep(String reason) {
        return reason == null ? null : clamp(reason);
    }
}

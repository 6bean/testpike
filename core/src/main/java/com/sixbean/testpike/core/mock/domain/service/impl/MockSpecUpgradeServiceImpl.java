package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.component.MockRuleKeys;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.component.SpecUpgradePlan;
import com.sixbean.testpike.core.mock.domain.service.MockSpecUpgradeService;
import com.sixbean.testpike.core.mock.domain.service.MockStubImportService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import com.sixbean.testpike.core.spec.domain.component.SpecDiff;
import com.sixbean.testpike.core.spec.domain.component.SpecDiffOps;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contract-upgrade planner implementation.
 * <p>The three-state split reuses the spec domain's single diff source
 * ({@link SpecDiffOps}) against the endpoint snapshots already stored on the
 * version rows — the parser never re-runs and no LLM token is spent, so the
 * plan is byte-deterministic and identical to what the upload badge saw.
 * <p>Rules belonging to the space are matched by provenance (source=openapi)
 * and by walking the contract's version chain (prev_spec_id upward, capped),
 * so an upgraded contract recognises the rules its own earlier versions
 * imported instead of treating them as foreign.
 * <p>Marking retired rules never deletes rows and never touches the hit
 * ledger: the rule keeps its identity for traffic history while the stale
 * flag plus the disabled switch take it out of serving, and a replay of the
 * same call files zero changes.
 */
@Service
public class MockSpecUpgradeServiceImpl implements MockSpecUpgradeService {

    /** Version-chain walk cap: a family deeper than this is data corruption, not reality. */
    private static final int FAMILY_WALK_CAP = 50;

    private final ApiSpecService apiSpecService;
    private final MockStubService stubService;
    private final MockStubImportService importService;
    private final NamedParameterJdbcTemplate jdbc;

    public MockSpecUpgradeServiceImpl(ApiSpecService apiSpecService, MockStubService stubService,
                                      MockStubImportService importService,
                                      NamedParameterJdbcTemplate jdbc) {
        this.apiSpecService = apiSpecService;
        this.stubService = stubService;
        this.importService = importService;
        this.jdbc = jdbc;
    }

    @Override
    public SpecUpgradePlan planDiff(long spaceId, long specId) {
        ApiSpecInfo current = apiSpecService.get(specId);
        if (current.prevSpecId() == null) {
            return SpecUpgradePlan.noPrevious();
        }
        ApiSpecInfo previous = apiSpecService.get(current.prevSpecId());
        SpecDiff diff = SpecDiffOps.diffOf(parsed(previous), parsed(current));
        // Identity diff alone keeps a pure response-schema reshuffle invisible
        // (structure hash only carries the body type summary). Mock rules
        // serve bodies, so the planner widens "changed" with body-material
        // drift; the badge semantics in the spec domain stay untouched.
        Set<String> changedKeys = new LinkedHashSet<>(diff.changed());
        changedKeys.addAll(bodyDriftKeys(previous, current));
        if (diff.isEmpty() && changedKeys.isEmpty()) {
            return new SpecUpgradePlan(List.of(), List.of(), List.of(), true);
        }

        Map<String, MockStubInfo> served = servedByFamily(spaceId, specId);
        Map<String, MockStubDraft> drafts = new LinkedHashMap<>();
        for (MockStubDraft draft : importService.previewDrafts(specId)) {
            drafts.put(MockRuleKeys.of(draft.method(), draft.pathPattern()), draft);
        }

        List<MockStubDraft> addedDrafts = new ArrayList<>();
        for (String key : diff.added()) {
            MockStubDraft draft = drafts.get(key);
            if (draft != null && !served.containsKey(key)) {
                addedDrafts.add(draft);
            }
        }
        List<Long> changedStubIds = new ArrayList<>();
        for (String key : changedKeys) {
            MockStubInfo stub = served.get(key);
            if (stub != null && drafts.containsKey(key)) {
                changedStubIds.add(stub.id());
            }
        }
        List<Long> removedStubIds = new ArrayList<>();
        for (String key : diff.removed()) {
            MockStubInfo stub = served.get(key);
            if (stub != null) {
                removedStubIds.add(stub.id());
            }
        }
        return new SpecUpgradePlan(addedDrafts, changedStubIds, removedStubIds, true);
    }

    @Override
    @Transactional
    public int markRemovedStale(long spaceId, long specId) {
        List<Long> ids = planDiff(spaceId, specId).removedStubIds();
        if (ids.isEmpty()) {
            return 0;
        }
        // Disable + flag only rows that are not in that state yet, so replaying
        // the same call changes nothing and the optimistic version never spins.
        return jdbc.update("""
                UPDATE tp_mock_stub
                   SET contract_stale = TRUE,
                       enabled        = FALSE,
                       version        = version + 1,
                       updated_at     = now()
                 WHERE id IN (:ids)
                   AND (contract_stale IS DISTINCT FROM TRUE
                        OR enabled IS DISTINCT FROM FALSE)
                """, new MapSqlParameterSource("ids", ids));
    }

    /** Space rules imported from this contract's version family, keyed by rule identity. */
    private Map<String, MockStubInfo> servedByFamily(long spaceId, long specId) {
        Set<String> familyRefs = contractFamilyRefs(specId);
        Map<String, MockStubInfo> byKey = new LinkedHashMap<>();
        for (MockStubInfo stub : stubService.listBySpace(spaceId)) {
            if (MockStubDraft.SOURCE_OPENAPI.equals(stub.source())
                    && familyRefs.contains(stub.sourceRef())) {
                byKey.put(MockRuleKeys.of(stub.method(), stub.pathPattern()), stub);
            }
        }
        return byKey;
    }

    /** spec:{id} provenance anchors of the version chain upward from specId (capped). */
    private Set<String> contractFamilyRefs(long specId) {
        Set<String> refs = new LinkedHashSet<>();
        Long cursor = specId;
        while (cursor != null && refs.size() < FAMILY_WALK_CAP) {
            ApiSpecInfo node = apiSpecService.get(cursor);
            refs.add("spec:" + node.id());
            cursor = node.prevSpecId();
        }
        return refs;
    }

    /**
     * Keys present in both versions whose served body material moved
     * (request schema or the $ref-inlined response trees) while the identity
     * diff stayed silent. Map.toString over the stored trees is stable for
     * equal content because both sides come from the same parser ordering.
     */
    private static Set<String> bodyDriftKeys(ApiSpecInfo previous, ApiSpecInfo current) {
        Map<String, String> prevBody = bodyMaterialBy(endpointsOf(previous));
        Map<String, String> curBody = bodyMaterialBy(endpointsOf(current));
        Set<String> drift = new LinkedHashSet<>(prevBody.keySet());
        drift.retainAll(curBody.keySet());
        drift.removeIf(key -> prevBody.get(key).equals(curBody.get(key)));
        return drift;
    }

    private static List<ApiEndpoint> endpointsOf(ApiSpecInfo info) {
        return info.endpoints() == null ? List.of() : info.endpoints();
    }

    private static Map<String, String> bodyMaterialBy(List<ApiEndpoint> endpoints) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ApiEndpoint ep : endpoints) {
            out.put(ep.endpointKey(), ep.requestSchema() + "||" + ep.responseSchemaTrees());
        }
        return out;
    }

    /** Snapshot-shaped view of a stored version row for the shared diff source. */
    private static ParsedApiSpec parsed(ApiSpecInfo info) {
        return new ParsedApiSpec(info.specFormat(), info.specTitle(),
                String.valueOf(info.specVersion()),
                info.endpoints() == null ? List.of() : info.endpoints());
    }
}

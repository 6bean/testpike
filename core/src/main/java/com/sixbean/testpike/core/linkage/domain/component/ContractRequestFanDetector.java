package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.linkage.domain.service.LinkageEdgeDetector;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bean detector behind edge api_spec->apitest_request: a contract version
 * edit fans out to every imported request in the same project whose stored
 * folded match key equals one of the spec's endpoint keys.
 *
 * <p>Kind 1 (bean) rather than a SQL row because the folded-key rules live in
 * Java (ContractMatchKeyRules via ContractSpecFoldedKeys) — the same reason
 * the impact close path in ApiSpecChangeAnalysisServiceImpl consumes them.
 * Bean name = default component name, which is what the ledger row's
 * detector_ref must spell.
 */
@Component
public class ContractRequestFanDetector implements LinkageEdgeDetector {

    private static final String SELECT_REQUESTS = """
            SELECT rq.id
              FROM tp_apitest_request rq
              JOIN tp_apitest_collection cq ON cq.id = rq.collection_id
             WHERE cq.project_id = :projectId
               AND rq.contract_endpoint_key IN (:keys)
            """;

    private final NamedParameterJdbcTemplate named;
    private final ContractSpecFoldedKeys specFoldedKeys;

    public ContractRequestFanDetector(NamedParameterJdbcTemplate named,
                                      ContractSpecFoldedKeys specFoldedKeys) {
        this.named = named;
        this.specFoldedKeys = specFoldedKeys;
    }

    @Override
    public List<Long> detectDownstream(Long upstreamObjectId) {
        return specFoldedKeys.armedKeys(upstreamObjectId)
                .map(this::requestsMatching)
                .orElse(List.of());
    }

    /**
     * Imported requests in the spec's own project stored under one of the
     * spec's folded endpoint keys. The key column is written by the same
     * ContractMatchKeyRules the shared folded-key component applies, so the
     * comparison needs no second fold here.
     */
    private List<Long> requestsMatching(ContractSpecFoldedKeys.SpecFanKeys keys) {
        return named.queryForList(SELECT_REQUESTS,
                new MapSqlParameterSource()
                        .addValue("projectId", keys.projectId())
                        .addValue("keys", keys.foldedKeys()),
                Long.class);
    }
}

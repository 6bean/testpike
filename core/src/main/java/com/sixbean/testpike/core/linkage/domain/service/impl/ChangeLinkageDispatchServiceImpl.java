package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInfo;
import com.sixbean.testpike.core.linkage.api.dto.LinkageImpactSummary;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.domain.service.ChangeLinkageDispatchService;
import com.sixbean.testpike.core.linkage.domain.service.ChangeLinkageEdgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Edge-driven downstream detection .
 * <p>Everything this pass may do is described by registered edge rows: the
 * trigger-field predicate decides whether the announced diff arms the edge
 * (an empty diff means the producer could not compute one, so the conservative
 * reading is "armed"), the detector predicate enumerates the downstream rows,
 * and the disposition strategy decides the ledger shape — never an update of
 * the downstream business table itself. Risk 1 writes the reversible stale
 * mark, risk 2 the pending grouped-draft row that only the one-click accept
 * consumes, risk 3 the disclosure row for issued records.
 * <p>Rows are keyed by (edge, downstream object type, downstream id): a newer
 * upstream version re-arms a consumed mark, a replayed version writes nothing
 * (a replay is not a change fact). Unknown detector kinds are logged, not
 * silently skipped — a rotting ledger must surface as a visible log signature
 * for the noise gate rather than pass as "no impact".
 */
@Service
public class ChangeLinkageDispatchServiceImpl implements ChangeLinkageDispatchService {

    private static final Logger log =
            LoggerFactory.getLogger(ChangeLinkageDispatchServiceImpl.class);

    /** Detector kind 2 = SQL predicate text stored in detector_ref (ledger data). */
    private static final int DETECTOR_KIND_SQL = 2;

    /**
     * Detector kind 1 = detector_ref names a live Spring bean implementing
     * {@link com.sixbean.testpike.core.linkage.domain.service.LinkageEdgeDetector}.
     * Used when the comparison lives in Java (folded endpoint-key rules) and
     * re-implementing it as SQL text would fork the judgment source.
     */
    private static final int DETECTOR_KIND_BEAN = 1;

    private static final String UPSERT_IMPACT = """
            INSERT INTO tp_linkage_impact (edge_key, upstream_object_type, upstream_object_id,
                    upstream_version, downstream_object_type, downstream_id,
                    disposition_strategy, status)
            VALUES (:edgeKey, :upstreamType, :upstreamId, :upstreamVersion,
                    :downstreamType, :downstreamId, :strategy, :status)
            ON CONFLICT (edge_key, downstream_object_type, downstream_id) DO UPDATE
               SET upstream_object_type = EXCLUDED.upstream_object_type,
                   upstream_object_id   = EXCLUDED.upstream_object_id,
                   upstream_version     = EXCLUDED.upstream_version,
                   disposition_strategy = EXCLUDED.disposition_strategy,
                   status               = EXCLUDED.status,
                   annotated_at         = now(),
                   consumed_at          = NULL
             WHERE tp_linkage_impact.upstream_version < EXCLUDED.upstream_version
            """;

    /** Presence probe that only splits created vs re-armed in the account. */
    private static final String EXISTS_IMPACT = """
            SELECT count(*) FROM tp_linkage_impact
             WHERE edge_key = :edgeKey AND downstream_object_type = :downstreamType
               AND downstream_id = :downstreamId
            """;

    private static final String SELECT_IMPACT_ID = """
            SELECT id FROM tp_linkage_impact
             WHERE edge_key = :edgeKey AND downstream_object_type = :downstreamType
               AND downstream_id = :downstreamId
            """;

    private final ChangeLinkageEdgeService edgeService;
    private final NamedParameterJdbcTemplate named;
    private final org.springframework.context.ApplicationContext context;

    public ChangeLinkageDispatchServiceImpl(ChangeLinkageEdgeService edgeService,
                                            NamedParameterJdbcTemplate named,
                                            org.springframework.context.ApplicationContext context) {
        this.edgeService = edgeService;
        this.named = named;
        this.context = context;
    }

    @Override
    @Transactional
    public LinkageImpactSummary detectDownstream(ObjectChangedEventMessage message) {
        if (message == null || message.objectId() == null || message.eventType() == null) {
            return new LinkageImpactSummary(0, 0, 0, 0, List.of());
        }
        String objectType = objectTypeToken(message.eventType());
        if (objectType == null) {
            log.warn("linkage dispatch dropped announcement with unknown eventType={} objectId={}",
                    message.eventType(), message.objectId());
            return new LinkageImpactSummary(0, 0, 0, 0, List.of());
        }
        List<ChangeLinkageEdgeInfo> edges = edgeService.findActiveEdges(objectType);
        int created = 0;
        int updated = 0;
        int found = 0;
        List<String> pendingDrafts = new java.util.ArrayList<>();
        for (ChangeLinkageEdgeInfo edge : edges) {
            if (!armsForDiff(edge, message.changedFields())) {
                continue;
            }
            List<Long> downstreamIds = runDetector(edge, message.objectId());
            found += downstreamIds.size();
            int[] write = writeImpacts(edge, message, downstreamIds, pendingDrafts);
            created += write[0];
            updated += write[1];
        }
        return new LinkageImpactSummary(edges.size(), created, updated, found, pendingDrafts);
    }

    private String objectTypeToken(String eventType) {
        try {
            return com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType
                    .valueOf(eventType).objectType();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The trigger-field predicate is a comma-separated field list (null = any
     * change arms the edge). An unknown (empty) diff arms conservatively so a
     * producer that cannot compute one never silences the fan-out.
     */
    private boolean armsForDiff(ChangeLinkageEdgeInfo edge, List<String> changedFields) {
        String predicate = edge.definition().triggerFieldPredicate();
        if (predicate == null || predicate.isBlank()
                || changedFields == null || changedFields.isEmpty()) {
            return true;
        }
        Set<String> watched = new LinkedHashSet<>(Arrays.asList(predicate.split(",")));
        watched.removeIf(s -> s == null || s.isBlank());
        if (watched.isEmpty()) {
            return true;
        }
        return changedFields.stream().filter(Objects::nonNull).anyMatch(watched::contains);
    }

    /**
     * Run one edge's detector. Kind 2 executes the stored SQL verbatim; kind 1
     * resolves detector_ref as a Spring bean. An unresolvable bean or an
     * unknown kind logs and arms nothing — ledger rot must surface in the log
     * (and in the orphan-edge scan), never pass as a silent no-impact.
     */
    private List<Long> runDetector(ChangeLinkageEdgeInfo edge, Long objectId) {
        String ref = edge.definition().detectorRef();
        Integer kind = edge.definition().detectorKind();
        if (Objects.equals(kind, DETECTOR_KIND_SQL)) {
            if (ref == null || ref.isBlank()) {
                log.warn("linkage dispatch skips edge {} with empty SQL detector_ref",
                        edge.definition().edgeKey());
                return List.of();
            }
            return named.queryForList(ref,
                    new MapSqlParameterSource("objectId", objectId), Long.class);
        }
        if (Objects.equals(kind, DETECTOR_KIND_BEAN)) {
            if (ref == null || ref.isBlank() || !context.containsBean(ref)) {
                log.warn("linkage dispatch skips edge {} because detector bean {} is absent "
                                + "(a bean-detector row must name a live LinkageEdgeDetector bean)",
                        edge.definition().edgeKey(), ref);
                return List.of();
            }
            Object bean = context.getBean(ref);
            if (!(bean instanceof com.sixbean.testpike.core.linkage.domain.service
                    .LinkageEdgeDetector detector)) {
                log.warn("linkage dispatch skips edge {} because bean {} does not implement "
                        + "LinkageEdgeDetector", edge.definition().edgeKey(), ref);
                return List.of();
            }
            return detector.detectDownstream(objectId);
        }
        log.warn("linkage dispatch skips edge {} with unsupported detector kind {} "
                        + "(ledger rot must surface, never pass as no-impact)",
                edge.definition().edgeKey(), kind);
        return List.of();
    }

    /** Strategy decides the ledger shape; statuses mirror LinkageImpactInfo javadoc. */
    private int[] writeImpacts(ChangeLinkageEdgeInfo edge, ObjectChangedEventMessage message,
                               List<Long> downstreamIds, List<String> pendingDrafts) {
        String strategy = edge.definition().dispositionStrategy();
        int status = switch (strategy) {
            case "mark_only" -> 1;
            case "grouped_draft" -> 2;
            case "disclose_only", "notify" -> 3;
            default -> 0;
        };
        if (status == 0) {
            log.warn("linkage dispatch skips edge {} with unhandled disposition {}",
                    edge.definition().edgeKey(), strategy);
            return new int[] {0, 0};
        }
        int created = 0;
        int updated = 0;
        for (Long downstreamId : downstreamIds) {
            Integer seen = named.queryForObject(EXISTS_IMPACT,
                    new MapSqlParameterSource()
                            .addValue("edgeKey", edge.definition().edgeKey())
                            .addValue("downstreamType", edge.definition().downstreamObjectType())
                            .addValue("downstreamId", downstreamId), Integer.class);
            int written = named.update(UPSERT_IMPACT, new MapSqlParameterSource()
                    .addValue("edgeKey", edge.definition().edgeKey())
                    .addValue("upstreamType", objectTypeToken(message.eventType()))
                    .addValue("upstreamId", message.objectId())
                    .addValue("upstreamVersion", versionOf(message))
                    .addValue("downstreamType", edge.definition().downstreamObjectType())
                    .addValue("downstreamId", downstreamId)
                    .addValue("strategy", strategy)
                    .addValue("status", status));
            if (written > 0) {
                if (seen != null && seen > 0) {
                    updated++;
                } else {
                    created++;
                }
                if (status == 2) {
                    // only a real write arms the repair-chain trigger: a replayed
                    // version wrote nothing above and must not ask twice
                    pendingDrafts.add(edge.definition().downstreamObjectType() + ":" + downstreamId);
                }
            }
        }
        return new int[] {created, updated};
    }

    private Integer versionOf(ObjectChangedEventMessage message) {
        return message.objectVersion() == null ? 0 : message.objectVersion();
    }
}

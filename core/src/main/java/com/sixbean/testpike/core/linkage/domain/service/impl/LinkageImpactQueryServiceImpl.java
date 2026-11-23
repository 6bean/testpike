package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.linkage.LinkageDraftImpact;
import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;
import com.sixbean.testpike.core.linkage.api.dto.UpstreamOwnership;
import com.sixbean.testpike.core.linkage.domain.component.LinkageImpactRowMapper;
import com.sixbean.testpike.core.linkage.domain.component.LinkageUpstreamOwnership;
import com.sixbean.testpike.core.linkage.domain.service.LinkageImpactQueryService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Ledger read for the display side . One statement, no caching: the
 * impact table is the single source and the panel must never show a row a
 * consumption already removed. Ordering by id keeps the grouped-card sequence
 * stable across reloads (the same upstream save always produces the same
 * card order).
 */
@Service
public class LinkageImpactQueryServiceImpl implements LinkageImpactQueryService {

    /** Pending = never consumed; status 1/2/3 are the pending faces. */
    private static final String SELECT_PENDING = """
            SELECT %s
            FROM tp_linkage_impact
             WHERE upstream_object_type = :upstreamType
               AND upstream_object_id   = :upstreamId
               AND consumed_at IS NULL
             ORDER BY id
            """.formatted(LinkageImpactRowMapper.COLUMNS);


    private final NamedParameterJdbcTemplate named;
    private final LinkageUpstreamOwnership ownership;

    public LinkageImpactQueryServiceImpl(NamedParameterJdbcTemplate named,
                                         LinkageUpstreamOwnership ownership) {
        this.named = named;
        this.ownership = ownership;
    }

    @Override
    public List<LinkageImpactInfo> findPendingByUpstream(String upstreamObjectType,
                                                         long upstreamObjectId) {
        if (upstreamObjectType == null || upstreamObjectType.isBlank()) {
            return List.of();
        }
        return named.query(SELECT_PENDING,
                new MapSqlParameterSource()
                        .addValue("upstreamType", upstreamObjectType.trim())
                        .addValue("upstreamId", upstreamObjectId),
                new LinkageImpactRowMapper());
    }

    /**
 * Draft-keyed live read . One statement: the join on
 * the mint's own anchor key ({@code context_json->>'linkageImpactId'})
 * is what defines a linkage card — anything else a human accepts from
 * the chat dock simply has no ledger row to disclose, so it is absent
 * from the answer rather than answered with a fabricated one. No status
 * filter here on purpose: the grouped card shows the ledger AS IT IS at
 * read time, a settled row with its consumed_at and a pending row with
 * null; hiding the settled state would fake a still-open consequence.
 */
    @Override
    public List<LinkageDraftImpact> findImpactsByDrafts(List<Long> draftIds) {
        if (draftIds == null || draftIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT d.id AS draft_id, d.project_id AS draft_project_id, i."
                + LinkageImpactRowMapper.COLUMNS.replace(", ", ", i. ")
                + " FROM tp_ai_chat_draft d"
                + " JOIN tp_linkage_impact i"
                + "   ON i.id::text = d.context_json->>'linkageImpactId'"
                + " WHERE d.id IN (:ids) ORDER BY d.id";
        return named.query(sql, new MapSqlParameterSource("ids", draftIds),
                (rs, n) -> new LinkageDraftImpact(rs.getLong("draft_id"),
                        (Long) rs.getObject("draft_project_id", Long.class),
                        new LinkageImpactRowMapper().mapRow(rs, n)));
    }

    @Override
    public LinkagePendingImpacts findPendingViewByUpstream(String upstreamObjectType,
                                                           long upstreamObjectId) {
        List<LinkageImpactInfo> pending = findPendingByUpstream(upstreamObjectType, upstreamObjectId);
        if (pending.isEmpty()) {
            // zero impact = nothing to disclose an owner of; the account stays silent
            return new LinkagePendingImpacts(null, List.of());
        }
        String kind = upstreamObjectType.trim();
        if (!ownership.knows(kind)) {
            throw new ServiceException("上游对象类型 " + kind + " 还没有可解析的归属项目路径，"
                    + "请先在变更联动边账中登记该类型的边", BizErrorCode.VALIDATION_ERROR);
        }
        UpstreamOwnership owner = ownership.resolve(kind, upstreamObjectId)
                .orElseThrow(() -> new ServiceException("上游" + kind + "对象 " + upstreamObjectId
                        + " 已不存在或不再指向任何项目，联动账无法展示", BizErrorCode.NOT_FOUND));
        return new LinkagePendingImpacts(owner.projectId(), pending);
    }
}

package com.sixbean.testpike.core.mock.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.mock.api.dto.MockHitInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.mock.domain.component.HitDraftMapper;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.service.MockHitPromoteService;
import com.sixbean.testpike.core.mock.domain.service.MockStubImportService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Promote-door implementation .
 * <p>The row is re-read from tp_mock_hit_log by id (one query, jsonb facets
 * parsed exactly like the read side — snapshots pass through as stored, no
 * re-masking on the plane's already-desensitized values), mapped through the
 * shared {@link HitDraftMapper}, and filed via the single
 * {@link MockStubImportService#importDrafts} channel — idempotence and the
 * MockRuleValidator gate come from there, nothing is added on top (red
 * line 6: one rule source, one validator).
 * <p>Space discipline: the caller declares the space, the ledger row is the
 * authority — a mismatch is refused loudly because filing a promotion into the
 * wrong space would leak one project's traffic shape into another's rules.
 */
@Service
public class MockHitPromoteServiceImpl implements MockHitPromoteService {

    private static final TypeReference<Map<String, Object>> SNAPSHOT_TYPE =
            new TypeReference<>() {
            };

    private final NamedParameterJdbcTemplate jdbc;
    private final MockStubImportService stubImportService;
    private final ObjectMapper objectMapper;
    private final HitDraftMapper mapper = new HitDraftMapper();

    public MockHitPromoteServiceImpl(NamedParameterJdbcTemplate jdbc,
                                     MockStubImportService stubImportService,
                                     ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.stubImportService = stubImportService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public MockStubImportResult promote(long spaceId, long hitId) {
        MockHitInfo hit = findRow(hitId);
        if (hit.spaceId() == null || hit.spaceId() != spaceId) {
            throw new ServiceException("该命中记录（" + hitId + "）属于空间 " + hit.spaceId()
                    + "，与所选空间 " + spaceId + " 不一致，请刷新页面后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        MockStubDraft draft = mapper.toDraft(hit);
        if (draft == null) {
            throw new ServiceException("这条请求没有可固化的响应（无响应面记录）——可能是转发失败的"
                    + "传输错误行；没有录到的应答不能凭空编成 stub，请挑一条有状态码的记录",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return stubImportService.importDrafts(spaceId, List.of(draft));
    }

    /** One ledger row by id — ghost ids refused, never answered with a phantom. */
    private MockHitInfo findRow(long hitId) {
        List<MockHitInfo> rows = jdbc.query("""
                SELECT id, space_id, stub_id, request_snapshot::text AS request_snapshot,
                       response_snapshot::text AS response_snapshot,
                       matched, latency_ms, fault_applied, session_id, hit_at, source
                FROM tp_mock_hit_log WHERE id = :id
                """, new MapSqlParameterSource("id", hitId), this::toInfo);
        if (rows.isEmpty()) {
            throw new ServiceException("命中记录 " + hitId + " 不存在或已被清理，无法转为 stub",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /** Same shaping as the read side (trail is not needed for the promote map). */
    private MockHitInfo toInfo(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MockHitInfo(rs.getLong("id"), rs.getLong("space_id"),
                (Long) rs.getObject("stub_id"),
                parseSnapshot(rs.getString("request_snapshot")),
                parseSnapshot(rs.getString("response_snapshot")),
                rs.getObject("matched", Boolean.class),
                List.of(),
                rs.getObject("latency_ms", Integer.class),
                rs.getString("fault_applied"), rs.getString("session_id"),
                rs.getObject("hit_at", LocalDateTime.class),
                rs.getString("source"));
    }

    private Map<String, Object> parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, SNAPSHOT_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}

package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.core.mock.domain.component.MockRuntimeRefusals;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingInfo;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingPageView;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.core.mock.domain.service.MockRecordingService;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockRecordingDO;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Recording control-plane implementation .
 * <p>Gate order at start is the abuse-resistance contract (design §9): space
 * existence first, then published-state (draft spaces have no served traffic
 * to record), then target shape, then the SSRF pre-gate pair — an EMPTY
 * {@code mock.proxy.allowlist} refuses fail-closed and a host outside the
 * allowlist is refused by name — and finally the one-open-box-per-space rule.
 * Host matching reuses ProdBlacklistGuard.hostMatches/hostOf so allow and
 * deny spectra share one wildcard semantics (red line 6, no second matcher).
 * <p>Open and seal are pure control-plane writes (stamps + status); the
 * capture/forward behaviour that fills summary_json and cassette_ref is .
 */
@Service
public class MockRecordingServiceImpl implements MockRecordingService {

    /** K8 hot-update key: comma-separated allow patterns (exact or *.domain). */
    static final String ALLOWLIST_KEY = "mock.proxy.allowlist";

    private static final String STATUS_RECORDING = "recording";
    private static final String STATUS_STOPPED = "stopped";

    private static final BeanPropertyRowMapper<MockRecordingDO> BOX_MAPPER =
            new BeanPropertyRowMapper<>(MockRecordingDO.class);

    private static final String BOX_COLUMNS = """
            id, space_id, target_base_url, status, summary_json::text AS summaryJson,
            cassette_ref, promoted_count, started_at, ended_at, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final MockSpaceService spaceService;
    private final SystemConfigReader configReader;
    private final com.sixbean.testpike.core.mock.domain.component.MockRecordingFrameStore frameStore;

    public MockRecordingServiceImpl(NamedParameterJdbcTemplate jdbc, MockSpaceService spaceService,
                                    SystemConfigReader configReader,
                                    com.sixbean.testpike.core.mock.domain.component.MockRecordingFrameStore frameStore) {
        this.jdbc = jdbc;
        this.spaceService = spaceService;
        this.configReader = configReader;
        this.frameStore = frameStore;
    }

    @Override
    @Transactional
    public long start(long spaceId, String targetBaseUrl) {
        // Gate 1 — ghost space refused by the space service's own wording.
        spaceService.get(spaceId);
        // Gate 2 — only a published space serves traffic worth recording.
        Integer status = jdbc.queryForObject("SELECT status FROM tp_mock_space WHERE id = :sid",
                new MapSqlParameterSource("sid", spaceId), Integer.class);
        if (status == null || status != 1) {
            throw new ServiceException(MockRuntimeRefusals.recordingSpaceNotPublished(spaceId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Gate 3 — target shape: absolute http(s) URL with a host.
        String target = targetBaseUrl == null ? "" : targetBaseUrl.trim();
        if (target.isBlank()) {
            throw new ServiceException(MockRuntimeRefusals.recordingTargetBlank(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        URI parsed = parseHttpUri(target);
        String host = ProdBlacklistGuard.hostOf(target);
        if (parsed == null || host == null) {
            throw new ServiceException(MockRuntimeRefusals.recordingTargetIllegal(target),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Gate 4 — fail-closed SSRF pre-gate: no allowlist configured = proxy off.
        String allowlist = configReader.getString(ALLOWLIST_KEY, "");
        if (allowlist.isBlank()) {
            throw new ServiceException("录制/代理默认关闭：系统配置「mock.proxy.allowlist」尚未配置允许的上游主机白名单，"
                    + "请先由管理员配置白名单（逗号分隔，支持 *.example.com 通配）",
                    BizErrorCode.FORBIDDEN);
        }
        // Gate 5 — host must match one allow pattern (same matcher as blacklist).
        boolean allowed = false;
        for (String pattern : allowlist.split(",")) {
            if (!pattern.isBlank() && ProdBlacklistGuard.hostMatches(host, pattern)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new ServiceException("录制目标主机「" + host + "」不在允许白名单（mock.proxy.allowlist）内，"
                    + "已拒绝以防服务端请求伪造；需要录制该上游请管理员先加入白名单",
                    BizErrorCode.FORBIDDEN);
        }
        // Gate 6 — one open box per space: seal the old one before opening anew.
        Integer open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_recording WHERE space_id = :sid AND status = :st",
                new MapSqlParameterSource().addValue("sid", spaceId).addValue("st", STATUS_RECORDING),
                Integer.class);
        if (open != null && open > 0) {
            throw new ServiceException(MockRuntimeRefusals.recordingBoxAlreadyOpen(spaceId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Gate 7 — persist the open box and bind the space's proxy target.
        LocalDateTime now = LocalDateTime.now();
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_mock_recording (space_id, target_base_url, status, started_at, updated_at)
                VALUES (:sid, :target, 'recording', :now, :now)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("sid", spaceId)
                .addValue("target", target)
                .addValue("now", now), Long.class);
        jdbc.update("UPDATE tp_mock_space SET proxy_target_url = :target, updated_at = :now WHERE id = :sid",
                new MapSqlParameterSource()
                        .addValue("target", target)
                        .addValue("now", now)
                        .addValue("sid", spaceId));
        return id;
    }

    @Override
    @Transactional
    public MockRecordingInfo stop(long recordingId) {
        MockRecordingDO box = findBox(recordingId);
        if (!STATUS_RECORDING.equals(box.getStatus())) {
            throw new ServiceException(
                    MockRuntimeRefusals.recordingBoxAlreadySealed(recordingId, box.getStatus()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                UPDATE tp_mock_recording
                   SET status = :stopped, ended_at = :now, updated_at = :now
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("stopped", STATUS_STOPPED)
                .addValue("now", now)
                .addValue("id", recordingId));
        sealCassette(recordingId);
        return toInfo(findBox(recordingId));
    }

    /**
 * Seal synthesis : drain the staged frame hold, upload
 * one JSONL cassette to object storage, stamp cassette_ref, and clear the
 * Redis hold. An empty box seals honestly with no cassette (NULL stays
 * distinguishable from a stub file — "nothing was recorded" is a fact).
 */
    private void sealCassette(long recordingId) {
        List<com.sixbean.testpike.common.mq.MockRecordingFrameMessage> frames =
                frameStore.drainAll(recordingId);
        if (frames.isEmpty()) {
            frameStore.clear(recordingId);
            return;
        }
        String objectKey = frameStore.uploadCassette(recordingId, frames);
        jdbc.update("UPDATE tp_mock_recording SET cassette_ref = :ref, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("ref", objectKey).addValue("id", recordingId));
        frameStore.clear(recordingId);
    }

    @Override
    public List<MockRecordingInfo> listBySpace(long spaceId) {
        // Same posture as the hit-query service: a ghost space is refused with
        // real wording instead of answering a pretend-empty page.
        spaceService.get(spaceId);
        return jdbc.query("SELECT " + BOX_COLUMNS
                        + " FROM tp_mock_recording WHERE space_id = :sid ORDER BY id DESC",
                new MapSqlParameterSource("sid", spaceId), BOX_MAPPER)
                .stream().map(this::toInfo).toList();
    }

    /**
 * Column keys the recording ledger may sort by -> SQL expressions (all
 * physical columns of tp_mock_recording; the jsonb summary blob stays
 * deliberately off-list — sorting a statistics payload has no honest
 * user-facing meaning). Values are never user input: off-list or
 * injection-shaped keys are refused by the shared fail-closed gate before
 * any SQL is built .
 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "target_base_url", "target_base_url",
            "status", "status",
            "promoted_count", "promoted_count",
            "started_at", "started_at",
            "ended_at", "ended_at",
            "created_at", "created_at",
            "updated_at", "updated_at");

    @Override
    public MockRecordingPageView listBySpacePaged(long spaceId, int page, int pageSize,
                                                  String sortBy, String sortDir) {
        // Ghost-space refusal is the same honesty rule as the unbounded list:
        // an unknown space answers with words, not with a pretend-empty window.
        spaceService.get(spaceId);
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource("sid", spaceId);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_recording WHERE space_id = :sid",
                params, Long.class);
        List<MockRecordingInfo> rows = jdbc.query("SELECT " + BOX_COLUMNS
                        + " FROM tp_mock_recording WHERE space_id = :sid"
                        + orderClause(sortBy, sortDir) + " LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()),
                BOX_MAPPER)
                .stream().map(this::toInfo).toList();
        return new MockRecordingPageView(rows, total == null ? 0L : total,
                window.page(), window.size());
    }

    /**
     * ORDER BY builder: no sort key keeps the historical newest-first id DESC
     * (the promotion counters and open-seal flows read positions off that
     * order); a whitelisted key orders by that column with the id DESC
     * tie-breaker so paging windows stay deterministic. The key itself passes
     * the shared fail-closed gate — only map VALUES reach the SQL string.
     */
    private static String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "录制盒列表");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    private MockRecordingDO findBox(long id) {
        List<MockRecordingDO> rows = jdbc.query("SELECT " + BOX_COLUMNS
                + " FROM tp_mock_recording WHERE id = :id",
                new MapSqlParameterSource("id", id), BOX_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException(MockRuntimeRefusals.recordingBoxMissing(id),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    private URI parseHttpUri(String target) {
        try {
            URI uri = URI.create(target);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase();
            return ("http".equals(scheme) || "https".equals(scheme)) ? uri : null;
        } catch (Exception e) {
            return null;
        }
    }

    private MockRecordingInfo toInfo(MockRecordingDO row) {
        return new MockRecordingInfo(row.getId(), row.getSpaceId(), row.getTargetBaseUrl(),
                row.getStatus(), row.getSummaryJson(), row.getCassetteRef(), row.getPromotedCount(),
                row.getStartedAt(), row.getEndedAt(), row.getCreatedAt(), row.getUpdatedAt());
    }
}

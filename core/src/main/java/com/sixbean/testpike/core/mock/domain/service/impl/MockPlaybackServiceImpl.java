package com.sixbean.testpike.core.mock.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.mock.api.dto.MockPlaybackResult;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.mock.domain.component.CassetteDraftMapper;
import com.sixbean.testpike.core.mock.domain.component.MockRecordingFrameStore;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.service.MockPlaybackService;
import com.sixbean.testpike.core.mock.domain.service.MockStubImportService;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockRecordingDO;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Cassette playback implementation .
 * <p>Read path mirrors the box state: while the box is open the frames still
 * live in the Redis hold ({@link MockRecordingFrameStore#drainAll}); after the
 * seal the hold is cleared and the uploaded JSONL cassette is the durable
 * truth — corrupt lines are skipped honestly (same posture as the hold reader).
 * A sealed box without a cassette recorded nothing: previews stay empty and
 * playback files nothing, because inventing rules from silence is the
 * forbidden alternative.
 * <p>Write path is delegation only: mapping comes from the shared
 * {@link CassetteDraftMapper} (strict/fuzzy semantics single-sourced with its
 * unit tests) and filing goes through
 * {@link MockStubImportService#importDrafts} — the same normal save chain the
 * contract-direct wizard uses (MockRuleValidator remains the one validation
 * point, idempotence and all-or-nothing rollbacks come free, red line 6).
 */
@Service
public class MockPlaybackServiceImpl implements MockPlaybackService {

    static final String MODE_STRICT = "strict";
    static final String MODE_FUZZY = "fuzzy";

    /** K8 hot-update key: newest-N frame cap for one AI induction sample. */
    static final String SAMPLE_MAX_FRAMES_KEY = "mock.ai.summarize.max-sample-frames";

    static final int DEFAULT_MAX_SAMPLE_FRAMES = 50;

    private static final String STATUS_RECORDING = "recording";

    private static final BeanPropertyRowMapper<MockRecordingDO> BOX_MAPPER =
            new BeanPropertyRowMapper<>(MockRecordingDO.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final MockRecordingFrameStore frameStore;
    private final MockStubImportService stubImportService;
    private final SystemConfigReader configReader;
    private final ObjectMapper objectMapper;
    private final CassetteDraftMapper mapper = new CassetteDraftMapper();

    public MockPlaybackServiceImpl(NamedParameterJdbcTemplate jdbc, MockRecordingFrameStore frameStore,
                                   MockStubImportService stubImportService,
                                   SystemConfigReader configReader, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.frameStore = frameStore;
        this.stubImportService = stubImportService;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MockStubDraft> previewDrafts(long recordingId, String mode) {
        MockRecordingDO box = findBox(recordingId);
        return draftsFor(box, requireMode(mode));
    }

    @Override
    @Transactional
    public MockPlaybackResult playback(long spaceId, long recordingId, String mode) {
        MockRecordingDO box = findBox(recordingId);
        // The caller declares the space; the box row is the authority. A
        // mismatch means the UI and the cassette disagree — refuse loudly
        // rather than filing rules into the wrong space.
        if (box.getSpaceId() == null || box.getSpaceId() != spaceId) {
            throw new ServiceException("录制盒与所选空间不一致（录制盒 " + recordingId
                    + " 属于空间 " + box.getSpaceId() + "），请刷新页面后重试，或在「仿真空间」打开录制盒所属的空间后再发起回放",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<MockStubDraft> drafts = draftsFor(box, requireMode(mode));
        MockStubImportResult importResult = stubImportService.importDrafts(spaceId, drafts);
        int accumulated = (box.getPromotedCount() == null ? 0 : box.getPromotedCount())
                + importResult.createdStubIds().size();
        jdbc.update("UPDATE tp_mock_recording SET promoted_count = :n, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("n", accumulated).addValue("id", recordingId));
        return new MockPlaybackResult(importResult.createdStubIds(),
                importResult.skippedEndpointKeys(), accumulated);
    }

    @Override
    public List<MockRecordingFrameMessage> sampleFrames(long recordingId) {
        MockRecordingDO box = findBox(recordingId);
        List<MockRecordingFrameMessage> frames = readFrames(box);
        int max = Math.max(1, configReader.getInt(SAMPLE_MAX_FRAMES_KEY, DEFAULT_MAX_SAMPLE_FRAMES));
        // Capture order is oldest-first; induction wants the newest tail.
        return frames.size() <= max ? frames
                : List.copyOf(frames.subList(frames.size() - max, frames.size()));
    }

    /** Mode intake: strict|fuzzy, case-insensitive, anything else refused by name. */
    private String requireMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODE_STRICT.equals(normalized) && !MODE_FUZZY.equals(normalized)) {
            throw new ServiceException("回放模式只支持 strict（严格按录制的每个 URL 一条规则）或 "
                    + "fuzzy（同端点归并为模板规则），收到：" + mode + "，请改成 strict 或 fuzzy 之一（回放入口：「仿真空间」空间工作台）", BizErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    /** Map the box's frames (hold while open, cassette after seal) with the chosen plan. */
    private List<MockStubDraft> draftsFor(MockRecordingDO box, String mode) {
        List<MockRecordingFrameMessage> frames = readFrames(box);
        return MODE_STRICT.equals(mode)
                ? mapper.strictDrafts(box.getId(), frames)
                : mapper.fuzzyDrafts(box.getId(), frames);
    }

    private List<MockRecordingFrameMessage> readFrames(MockRecordingDO box) {
        if (STATUS_RECORDING.equals(box.getStatus())) {
            return frameStore.drainAll(box.getId());
        }
        if (box.getCassetteRef() == null || box.getCassetteRef().isBlank()) {
            return List.of();
        }
        byte[] raw = frameStore.readCassetteBytes(box.getCassetteRef());
        if (raw == null) {
            // Reference stamped but object gone: honest refusal beats an
            // empty replay that would silently claim "nothing was recorded".
            throw new ServiceException("录制带（cassette）文件已丢失（" + box.getCassetteRef()
                    + "），无法回放；请重新录制（录制入口：「仿真空间」进入该空间工作台的录制面板）", BizErrorCode.VALIDATION_ERROR);
        }
        return parseJsonl(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** JSONL reader — corrupt lines dropped honestly, same posture as the hold drain. */
    private List<MockRecordingFrameMessage> parseJsonl(String text) {
        List<MockRecordingFrameMessage> out = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(objectMapper.readValue(line, MockRecordingFrameMessage.class));
            } catch (Exception e) {
                continue;
            }
        }
        return out;
    }

    private MockRecordingDO findBox(long id) {
        List<MockRecordingDO> rows = jdbc.query("""
                SELECT id, space_id, status, cassette_ref, promoted_count
                  FROM tp_mock_recording WHERE id = :id
                """, new MapSqlParameterSource("id", id), BOX_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("录制盒不存在或已被删除，请前往「仿真空间」进入对应空间的工作台重新选择录制盒", BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }
}

package com.sixbean.testpike.aiservice.devtoolspanel.domain.service;

import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.DevToolsPanelParseResult;
import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.LoginStateCandidate;
import com.sixbean.testpike.aiservice.devtoolspanel.api.dto.PanelStageOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.aiservice.ocr.domain.service.OcrEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one chat screenshot attachment into a transient staging slot of
 * parsed login-state rows . The dialog attachment chain
 * hands over already-fetched bytes; this service runs the server-side OCR
 * pass, the real grid aligner ({@link DevToolsPanelParseService}) and pushes
 * the freshly parsed rows — values included — through core's stage door so
 * they park in the same TTL-bound store family the CDP wizard owns (R40).
 * <p>Gate posture: switch {@code ai.chat.login-state-draft.enabled} (single
 * source constant, shared with the draft-kind map in ChatRunOrchestrator) —
 * closed means a silent pass-through BEFORE any OCR or door call: zero model
 * budget, zero Redis writes, the dialog turn keeps its exact old shape (R32
 * honest closure; the refusal guidance belongs to the draft door, not to an
 * incidental screenshot read).
 * <p>Value posture (red line): values transit this JVM, the door body and
 * the Redis slot — and stop there. The returned outcome carries only
 * kind/name/origin markers; nothing value-bearing is logged or rendered.
 * <p>Failure posture: any leg (OCR worker down, door unreachable) degrades
 * to {@link PanelStageOutcome#NONE} with one WARN line (message only, never
 * the rows) — this is an enhancement leg of the vision round-trip and must
 * never take the answer down.
 */
@Service
public class ScreenshotLoginStateStager {

    /** Draft-chain switch shared with the chat write-door gate map (single source). */
    public static final String KEY_LOGIN_STATE_GATE = "ai.chat.login-state-draft.enabled";

    /**
     * Staging provenance channel. Cross-module wire mirror of core's
     * {@code CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT} — ai-service never
     * imports core classes, so the literal pair lives on both sides of the
     * HTTP door and the mapping note here is the single reconciliation anchor.
     */
    public static final String CHANNEL_CHAT_SCREENSHOT = "chat-screenshot";

    private static final Logger log = LoggerFactory.getLogger(ScreenshotLoginStateStager.class);

    private final CoreGateway coreGateway;
    private final OcrEngineService ocrEngineService;
    private final DevToolsPanelParseService parseService;

    public ScreenshotLoginStateStager(CoreGateway coreGateway,
                                      OcrEngineService ocrEngineService,
                                      DevToolsPanelParseService parseService) {
        this.coreGateway = coreGateway;
        this.ocrEngineService = ocrEngineService;
        this.parseService = parseService;
    }

    /**
     * Parse one screenshot and park the clean rows under {@code chatSessionId}.
     * Cheap short-circuits first: blank session / blank bytes never even read
     * the switch (zero config reads keep photoless wiring free of door noise).
     */
    public PanelStageOutcome stageFromScreenshot(long chatSessionId, byte[] imageBytes) {
        if (chatSessionId <= 0 || imageBytes == null || imageBytes.length == 0) {
            return PanelStageOutcome.NONE;
        }
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_LOGIN_STATE_GATE))) {
            return PanelStageOutcome.NONE;
        }
        try {
            DevToolsPanelParseResult parsed = parseService.parse(ocrEngineService.extract(imageBytes));
            if (parsed.candidates().isEmpty()) {
                return PanelStageOutcome.NONE;
            }
            String token = coreGateway.stageScreenshotSlot(CHANNEL_CHAT_SCREENSHOT,
                    chatSessionId, toDoorRows(parsed.candidates()));
            return new PanelStageOutcome(token, describe(parsed.candidates()),
                    parsed.candidates().size(), parsed.truncatedSuspects().size());
        } catch (RuntimeException legFailure) {
            // Message only — the exception text of our own legs never carries
            // a row value, and third-party OCR errors are logged as-is.
            log.warn("截图登录态解析暂存降级 sessionId={} err={}",
                    chatSessionId, legFailure.getMessage());
            return PanelStageOutcome.NONE;
        }
    }

    /** Door rows: the CaptureCommittedEntry wire shape, values included. */
    private static List<Map<String, Object>> toDoorRows(List<LoginStateCandidate> candidates) {
        List<Map<String, Object>> rows = new ArrayList<>(candidates.size());
        for (LoginStateCandidate candidate : candidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", candidate.kind());
            row.put("name", candidate.name());
            row.put("origin", candidate.origin());
            row.put("value", candidate.value());
            row.put("expiresAtEpochSecond", toEpochSecond(candidate.expiresAt()));
            rows.add(row);
        }
        return rows;
    }

    /**
 * Raw expiry column text → epoch seconds, honestly null when the cell is
 * "Session" or free-form: the slot stores what we actually know ( D4
 * no-invention rule).
 */
    static Long toEpochSecond(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return Instant.parse(text).getEpochSecond();
        } catch (RuntimeException instantMiss) {
            // fall through to the local-date form
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (RuntimeException localMiss) {
            return null;
        }
    }

    /** Value-free row descriptions for the observation block and tests. */
    private static List<String> describe(List<LoginStateCandidate> candidates) {
        List<String> lines = new ArrayList<>(candidates.size());
        for (LoginStateCandidate candidate : candidates) {
            StringBuilder line = new StringBuilder(candidate.kind())
                    .append(' ').append(candidate.name());
            if (candidate.origin() != null && !candidate.origin().isBlank()) {
                line.append(" @ ").append(candidate.origin());
            }
            if (candidate.annotated()) {
                line.append("（已标红）");
            }
            lines.add(line.toString());
        }
        return lines;
    }
}

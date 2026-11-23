package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-message image replay scanner .
 * <p>What it answers, mechanically: "which pictures did this user paste into
 * this session recently?" The write face (core ChatRunServiceImpl) books one
 * state frame per image-carrying question with {@code meta_json.chatImages};
 * this component reads those metas back newest-first through the core
 * ownership-checked door, flattens the booked (objectKey, fileName) pairs,
 * drops repeats by objectKey and stops at the hot cap
 * {@code ai.chat.image-replay-cap} (read-side fallback {@value
 * #DEFAULT_REPLAY_CAP} when the key is absent or illegal).
 * <p>Postures that keep a text-only turn exactly as cheap as before a ledger
 * exists: a turn that already carries fresh pictures never opens the door
 * (the current attachments win, R40 single read path downstream); an empty
 * ledger or a momentarily unreachable door degrades honestly to "nothing to
 * replay" — the ledger can never be more important than the answer.
 */
@Component
public class ChatImageLedgerScanner {

    /** Replay cap K8 key (V511 seed, write-side range gate already in place). */
    static final String KEY_REPLAY_CAP = "ai.chat.image-replay-cap";
    /** Fallback cap when the key is absent/illegal. */
    static final int DEFAULT_REPLAY_CAP = 3;

    private static final Logger log = LoggerFactory.getLogger(ChatImageLedgerScanner.class);

    private final CoreGateway coreGateway;
    private final ObjectMapper ledgerReader = new ObjectMapper();

    public ChatImageLedgerScanner(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    /**
     * Candidates for one turn, newest first, de-duplicated, capped. The
     * caller passes the attachments the question itself carries: non-empty
     * means the ledger is not consulted at all (zero config reads, zero door
     * calls — the exact pre-slice shape stays observable in tests).
     */
    public List<ChatImageAttachment> replayCandidatesFor(long userId, long sessionId,
                                                         List<ChatImageAttachment> thisTurnAttachments) {
        if (thisTurnAttachments != null && !thisTurnAttachments.isEmpty()) {
            return List.of();
        }
        int cap = SysConfigInts.positiveInt(
                coreGateway.getSysConfig(KEY_REPLAY_CAP), DEFAULT_REPLAY_CAP);
        List<String> metas;
        try {
            metas = coreGateway.chatImageMetas(userId, sessionId, cap);
        } catch (RuntimeException ex) {
            log.warn("历史贴图台账读门暂不可用，本轮按无历史图回答: {}", ex.toString());
            return List.of();
        }
        List<ChatImageAttachment> candidates = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (String meta : metas) {
            if (candidates.size() >= cap) {
                break;
            }
            readBookedImages(meta, candidates, seenKeys, cap);
        }
        return List.copyOf(candidates);
    }

    /**
 * Ownership set for one session : the
 * objectKeys booked into this session's image ledger inside the scan
 * window. Deliberately NOT the replay posture: a door that cannot be read
 * never degrades to "nothing booked" here — the exception propagates so
 * the OCR caller fails closed instead of treating an unreadable ledger as
 * proof a handle is absent.
 */
    public java.util.Set<String> ownedImageKeys(long userId, long sessionId, int scanLimit) {
        List<String> metas = coreGateway.chatImageMetas(userId, sessionId, scanLimit);
        java.util.Set<String> keys = new HashSet<>();
        for (String meta : metas == null ? List.<String>of() : metas) {
            List<ChatImageAttachment> ignored = new ArrayList<>();
            readBookedImages(meta, ignored, keys, scanLimit);
        }
        return java.util.Set.copyOf(keys);
    }

    /**
     * One booked meta row → its (objectKey, fileName) pairs in booked order.
     * A row that cannot be parsed is skipped with a warning: one malformed
     * ledger entry never blinds the whole scan, and the write face builds the
     * JSON with Jackson so an unreadable row means external tampering, not a
     * user-chosen file name.
     */
    private void readBookedImages(String meta, List<ChatImageAttachment> candidates,
                                  Set<String> seenKeys, int cap) {
        if (meta == null || meta.isBlank()) {
            return;
        }
        try {
            JsonNode booked = ledgerReader.readTree(meta).path("chatImages");
            if (!booked.isArray()) {
                return;
            }
            for (JsonNode image : booked) {
                String objectKey = image.path("objectKey").asText("");
                if (objectKey.isBlank() || !seenKeys.add(objectKey)) {
                    continue;
                }
                candidates.add(new ChatImageAttachment(objectKey, image.path("fileName").asText("")));
                if (candidates.size() >= cap) {
                    return;
                }
            }
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            log.warn("历史贴图台账行不可解析，已跳过该行: {}", ex.toString());
        }
    }
}

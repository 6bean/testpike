package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cross-message image replay candidates .
 * The scanner is the ONLY door a text-only turn walks to pick up the pictures
 * booked on earlier turns of the same session; these four postures are what
 * keeps that door honest: the hot cap is respected, an absent key falls back
 * to three, a turn that already carries fresh pictures never touches the
 * ledger, and an empty ledger costs zero remote work.
 */
class ChatImageLedgerReplayTest {

    private static final long SESSION = 41L;
    private static final long USER = 7L;

    /** One booked meta row exactly as the write face stores it. */
    private static String meta(String... objectKeys) {
        StringBuilder json = new StringBuilder("{\"chatImages\":[");
        for (int i = 0; i < objectKeys.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"objectKey\":\"").append(objectKeys[i])
                    .append("\",\"fileName\":\"shot-").append(i + 1).append(".png\"}");
        }
        return json.append("]}").toString();
    }

    @Test
    void newestFirstFlatteningRespectsTheHotCapAndDeduplicatesKeys() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(ChatImageLedgerScanner.KEY_REPLAY_CAP)).thenReturn("2");
        // The door answers newest seq first; row one alone already carries two
        // booked pictures plus a repeat key, so only k1/k2 may survive.
        when(gateway.chatImageMetas(USER, SESSION, 2))
                .thenReturn(List.of(meta("k1", "k2", "k1"), meta("k3")));
        ChatImageLedgerScanner scanner = new ChatImageLedgerScanner(gateway);

        List<ChatImageAttachment> candidates =
                scanner.replayCandidatesFor(USER, SESSION, List.of());

        assertEquals(List.of("k1", "k2"),
                candidates.stream().map(ChatImageAttachment::objectKey).toList());
        assertEquals("shot-1.png", candidates.get(0).fileName());
    }

    @Test
    void absentCapKeyFallsBackToThree() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(ChatImageLedgerScanner.KEY_REPLAY_CAP)).thenReturn(null);
        when(gateway.chatImageMetas(anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(meta("k1"), meta("k2"), meta("k3", "k4", "k5")));
        ChatImageLedgerScanner scanner = new ChatImageLedgerScanner(gateway);

        List<ChatImageAttachment> candidates =
                scanner.replayCandidatesFor(USER, SESSION, List.of());

        assertEquals(3, candidates.size());
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(gateway).chatImageMetas(anyLong(), anyLong(), limit.capture());
        assertEquals(ChatImageLedgerScanner.DEFAULT_REPLAY_CAP, limit.getValue());
    }

    @Test
    void freshAttachmentsThisTurnSkipTheLedgerEntirely() {
        CoreGateway gateway = mock(CoreGateway.class);
        ChatImageLedgerScanner scanner = new ChatImageLedgerScanner(gateway);

        List<ChatImageAttachment> candidates = scanner.replayCandidatesFor(USER, SESSION,
                List.of(new ChatImageAttachment("fresh-1", "now.png")));

        assertTrue(candidates.isEmpty());
        verifyNoInteractions(gateway);
    }

    @Test
    void emptyLedgerAndABrokenDoorDegradeToNoReplay() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(ChatImageLedgerScanner.KEY_REPLAY_CAP)).thenReturn("3");
        when(gateway.chatImageMetas(USER, SESSION, 3)).thenReturn(List.of());
        ChatImageLedgerScanner scanner = new ChatImageLedgerScanner(gateway);

        assertTrue(scanner.replayCandidatesFor(USER, SESSION, List.of()).isEmpty());

        // A ledger door that momentarily cannot answer must not kill the turn
        // the user asked without pictures — it degrades to "nothing to replay".
        when(gateway.chatImageMetas(USER, SESSION, 3))
                .thenThrow(new IllegalStateException("core door unavailable"));
        assertTrue(scanner.replayCandidatesFor(USER, SESSION, List.of()).isEmpty());
    }
}

package com.sixbean.testpike.admin.aichat.app.component;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;

/**
 * Shared config-door fakes for the tail-f pump spectra (R40 single source, introduced
 * when the extraction and steps-gen bridges started consuming the same
 * {@code ProcessEventPump} and each test needed the same fast-pull hot keys).
 */
public final class StreamPumpConfigDoors {

    private StreamPumpConfigDoors() {
    }

    /** Fast-pull hot keys so the spectra finish in well under a second. */
    public static CoreAiChatClient fastConfigDoor() {
        return doorWithKeys(300, 3600);
    }

    /**
     * Quiet-guard spectra door: the same fast pacing with explicit
     * idle-milestone and single-window-cap seconds, so the guard spectra
     * finish in seconds without touching the shipped defaults.
     */
    public static CoreAiChatClient quietGuardConfigDoor(int idleSeconds, int tailMaxSeconds) {
        return doorWithKeys(idleSeconds, tailMaxSeconds);
    }

    private static CoreAiChatClient doorWithKeys(int idleSeconds, int tailMaxSeconds) {
        CoreAiChatClient config = mock(CoreAiChatClient.class);
        when(config.readConfigInt(anyString(), anyInt())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            int fallback = invocation.getArgument(1);
            return switch (key) {
                case "ai.stream.heartbeat-seconds" -> 1;
                case "ai.stream.poll-interval-ms" -> 50;
                case "ai.stream.status-probe-interval-ms" -> 200;
                case "ai.stream.idle-timeout-seconds" -> idleSeconds;
                case "ai.stream.tail-max-seconds" -> tailMaxSeconds;
                default -> fallback;
            };
        });
        return config;
    }
}

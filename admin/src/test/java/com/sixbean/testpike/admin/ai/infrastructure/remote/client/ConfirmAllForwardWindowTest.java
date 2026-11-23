package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forward-window contract for the full-store confirm door (red B of the golden
 * window, surface): a bulk landing of hundreds of preview items legitimately
 * runs longer than the 30s default internal read window, so the forwarding leg
 * must carry its own configurable window and, when that window is exceeded,
 * answer with a rejection that names the cause, the current window and the
 * adjustable key — instead of the generic "downstream unreachable" 500 that
 * made users believe the service was dead while core was still working.
 * Idempotent replay (already-landed items skip) is stated in the same sentence
 * so a retry click is safe by design.
 */
class ConfirmAllForwardWindowTest {

    private static final String KEY = "ai.document.confirm-all-forward-read-timeout-seconds";

    @Test
    void forwardWindowComesFromConfigurationNotTheHardDefault() {
        RestCoreDocumentClient client = new RestCoreDocumentClient("http://localhost:8083", 42);
        assertThat(client.confirmAllForwardWindow()).isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    void windowExceededRejectionNamesCauseWindowKeyAndReplaySafety() {
        RestCoreDocumentClient client = new RestCoreDocumentClient("http://localhost:8083", 42);
        // The seam builds the rejection the forwarding catch throws; asserting the
        // produced object keeps the wording contract testable without a live socket.
        ServiceException rejection = client.confirmAllTimeoutRejection(8903L);
        assertThat(rejection).isNotNull();
        assertThat(rejection.getMessage())
                .contains("42")
                .contains(KEY)
                .contains("不会重复写入");
    }
}

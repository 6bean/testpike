package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stream-guard interrupt hygiene .
 * <p>When the idle watchdog closes a stalled SSE body, the JDK wakes the
 * parked reader with the interrupt flag left set. Before the fix that residue
 * rode out of consumeStream on the ServiceException path and instantly failed
 * every later JDBC fetch and retry on the same worker (same-second collapse of
 * three extraction attempts). The guard's own cancellation is the only thing
 * that may be cleared here: a pre-existing cooperative cancellation
 * must stay visible to downstream interrupt-aware
 * steps, so the success path never touches the flag.
 */
class OpenAiCompatClientStreamGuardInterruptTest {

    private final OpenAiCompatClient client = new OpenAiCompatClient(
            "http://127.0.0.1:1", "sk-test", Duration.ofSeconds(600),
            Duration.ofMillis(50), new ObjectMapper());

    private Object runConsumeStream(InputStream body) throws Exception {
        Method consumeStream = OpenAiCompatClient.class.getDeclaredMethod(
                "consumeStream", InputStream.class, Consumer.class,
                String.class, String.class, long.class);
        consumeStream.setAccessible(true);
        try {
            return consumeStream.invoke(client, body,
                    (Consumer<LlmStreamEvent>) event -> { }, "openai", "test-model", 0L);
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    @Test
    void guardBreachLeavesNoInterruptResidueForLaterJdbcSteps() throws Exception {
        assertFalse(Thread.interrupted(), "test must start on a clean interrupt flag");
        StreamInterruptingOnCloseBody body = new StreamInterruptingOnCloseBody();

        Object outcome = runConsumeStream(body);

        assertTrue(outcome instanceof ServiceException,
                "idle breach must surface through the shared timeout taxonomy");
        ServiceException failure = (ServiceException) outcome;
        assertEquals(BizErrorCode.AI_TIMEOUT, failure.getCode().intValue());
        assertFalse(Thread.interrupted(),
                "guard self-close residue must be cleared before the throw: "
                        + "otherwise the same worker fails every later JDBC/retry call");
    }

    @Test
    void cooperativeCancellationFlagSurvivesASuccessfulStream() throws Exception {
        InputStream doneFrame = new ByteArrayInputStream(
                ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        // Simulate a abort already in flight on this worker thread.
        Thread.currentThread().interrupt();

        Object outcome = runConsumeStream(doneFrame);

        assertTrue(outcome instanceof LlmChatResult, "stream must still parse: " + outcome);
        assertTrue(Thread.interrupted(),
                "consumeStream must never launder a cooperative cancellation flag");
    }

    @Test
    void nonGuardFailurePathKeepsItsOwnSemantics() throws Exception {
        // Silent stream that ends without [DONE] and without any breach: the
        // interruption is upstream's, not the guard's, and stays classified as
        // a provider error. No residue handling is involved on this path.
        InputStream truncated = new ByteArrayInputStream(
                "data: {\"choices\":[{\"delta\":{\"content\":\"half\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));

        Object outcome = runConsumeStream(truncated);

        assertNotNull(outcome);
        assertTrue(outcome instanceof ServiceException);
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR,
                ((ServiceException) outcome).getCode().intValue());
    }
}

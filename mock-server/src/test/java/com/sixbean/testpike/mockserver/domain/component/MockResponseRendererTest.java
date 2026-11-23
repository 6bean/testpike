package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.MockRequestContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response-builder consumption (design F3/F5): the stub response payload is a
 * JSON object with status / headers / bodyMode / body; static bodies pass
 * through raw, template bodies render against the served request through the
 * single common kernel, header values render too (echo headers are a real
 * use case), and every fail-closed shape (unknown mode, unparsable payload,
 * schema mode before its landing task) is refused with a human-readable
 * reason instead of serving a broken or pretended response.
 */
class MockResponseRendererTest {

    private final MockResponseRenderer renderer = new MockResponseRenderer();

    private MockRequestContext request() {
        return new MockRequestContext("GET", "/mocks/demo/orders/42",
                Map.of("orderId", "42"),
                Map.of("page", List.of("3")),
                Map.of("x-trace-id", "t-9"),
                null);
    }

    @Test
    void staticBodyPassesThroughWithoutTemplateRendering() {
        MockRenderedResponse out = renderer.render(
                "{\"status\":201,\"headers\":{\"X-Kind\":\"fixed\"},\"bodyMode\":\"static\","
                        + "\"body\":\"{{mock.query.page}} stays raw\"}",
                request(), new ArrayList<>());
        assertEquals(201, out.status());
        assertEquals("fixed", out.headers().get("X-Kind"));
        assertEquals("{{mock.query.page}} stays raw", out.body(),
                "static mode never renders placeholders");
    }

    @Test
    void templateBodyAndHeadersRenderAgainstTheServedRequest() {
        MockRenderedResponse out = renderer.render(
                "{\"status\":200,\"headers\":{\"X-Trace\":\"{{mock.header.x-trace-id}}\"},"
                        + "\"bodyMode\":\"template\",\"body\":\"{\\\"page\\\":\\\"{{mock.query.page}}\\\","
                        + "\\\"id\\\":\\\"{{mock.pathVar.orderId}}\\\"}\"}",
                request(), new ArrayList<>());
        assertEquals("t-9", out.headers().get("X-Trace"));
        assertEquals("{\"page\":\"3\",\"id\":\"42\"}", out.body());
    }

    @Test
    void missingModeOrFieldsFallBackToDefaultsHonestly() {
        MockRenderedResponse out = renderer.render(
                "{\"body\":\"plain\"}", request(), new ArrayList<>());
        assertEquals(200, out.status(), "default status 200");
        assertEquals("plain", out.body());
        assertTrue(out.headers().isEmpty());
    }

    @Test
    void genFamilyStaysUsableInsideTemplateBody_singleKernel() {
        MockRenderedResponse out = renderer.render(
                "{\"bodyMode\":\"template\",\"body\":\"h=${gen.sha1(text=abc)}\"}",
                request(), new ArrayList<>());
        assertEquals("h=a9993e364706816aba3e25717850c26c9cd0d89d", out.body());
    }

    @Test
    void randomSeedEventsLandInSharedSink() {
        List<Map<String, Object>> events = new ArrayList<>();
        renderer.render("{\"bodyMode\":\"template\",\"body\":\"{{mock.randomInt(1,9,seed=4)}}\"}",
                request(), events);
        assertEquals(List.of(Map.of("fn", "randomInt", "seed", 4L)), events);
    }

    @Test
    void schemaModeFailsClosedUntilItsLandingTask() {
        ServiceException ex = assertThrows(ServiceException.class, () -> renderer.render(
                "{\"bodyMode\":\"schema\",\"schema\":\"{\\\"type\\\":\\\"object\\\"}\"}",
                request(), new ArrayList<>()));
        assertTrue(ex.getMessage().contains("schema"),
                "the refusal must name the mode that is not served yet");
    }

    @Test
    void unparsablePayloadFailsClosedWithHumanReason() {
        assertThrows(ServiceException.class,
                () -> renderer.render("not-json", request(), new ArrayList<>()));
        assertThrows(ServiceException.class,
                () -> renderer.render(null, request(), new ArrayList<>()));
    }

    @Test
    void templateRenderingFailurePropagatesFailClosed() {
        ServiceException ex = assertThrows(ServiceException.class, () -> renderer.render(
                "{\"bodyMode\":\"template\",\"body\":\"{{mock.query.ghost}}\"}",
                request(), new ArrayList<>()));
        assertTrue(ex.getMessage().contains("ghost"),
                "the kernel names the missing reference — never a raw token served");
    }
}

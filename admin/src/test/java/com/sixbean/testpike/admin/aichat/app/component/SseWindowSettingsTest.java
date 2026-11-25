package com.sixbean.testpike.admin.aichat.app.component;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.sixbean.testpike.admin.testsupport.RepoSourceReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract for the single source of the live tail window: one settings bean
 * owns the ceiling, every stream door opens its emitter through it, and the
 * value is driven by the install-time key TESTPIKE_SSE_WINDOW_MILLIS.
 * The legacy hardcoded 30 minutes must stay as the shipped default (zero
 * behaviour drift) while operators can shorten the window for verification.
 */
class SseWindowSettingsTest {

    private static final long LEGACY_DEFAULT = 30L * 60L * 1000L;

    @Test
    void shippedDefaultKeepsTheLegacyThirtyMinuteCeiling() {
        SseEmitter emitter = new SseWindowSettings(LEGACY_DEFAULT).openEmitter();
        assertThat(emitter.getTimeout()).isEqualTo(LEGACY_DEFAULT);
    }

    @Test
    void shortWindowOverrideLandsOnEveryOpenedEmitter() {
        SseWindowSettings settings = new SseWindowSettings(15_000L);
        assertThat(settings.windowMillis()).isEqualTo(15_000L);
        assertThat(settings.openEmitter().getTimeout()).isEqualTo(15_000L);
    }

    @Test
    void nonPositiveWindowIsRefusedAtStartup() {
        assertThatThrownBy(() -> new SseWindowSettings(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sse.window-millis");
        assertThatThrownBy(() -> new SseWindowSettings(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installKeyDrivesTheBeanWithTheLegacyFallback() throws Exception {
        String settings = RepoSourceReader.read(
                "admin/src/main/java/com/sixbean/testpike/admin/aichat/app/component/SseWindowSettings.java");
        assertThat(settings).contains("@Value(\"${sse.window-millis:" + LEGACY_DEFAULT + "}\")");
        String yml = RepoSourceReader.read("admin/src/main/resources/application.yml");
        assertThat(yml).contains("window-millis: ${TESTPIKE_SSE_WINDOW_MILLIS:" + LEGACY_DEFAULT + "}");
        String envTemplate = RepoSourceReader.read(".env.example");
        assertThat(envTemplate).contains("TESTPIKE_SSE_WINDOW_MILLIS=" + LEGACY_DEFAULT);
    }

    @Test
    void allThreeStreamDoorsOpenThroughTheSharedBeanAndKeepNoPrivateConstant() throws Exception {
        for (String rel : new String[] {
                "admin/src/main/java/com/sixbean/testpike/admin/ai/api/controller/AiController.java",
                "admin/src/main/java/com/sixbean/testpike/admin/ai/api/controller/DocumentController.java",
                "admin/src/main/java/com/sixbean/testpike/admin/aichat/api/controller/AiChatStreamController.java"}) {
            String source = RepoSourceReader.read(rel);
            assertThat(source)
                    .as(rel + " must not keep a private timeout constant")
                    .doesNotContain("SSE_TIMEOUT_MILLIS")
                    .doesNotContain("EMITTER_TIMEOUT_MILLIS")
                    .doesNotContain("new SseEmitter(30")
                    .doesNotContain("30 * 60 * 1000L");
            assertThat(source)
                    .as(rel + " must open its emitter through the shared window bean")
                    .contains("openEmitter()")
                    .contains("SseWindowSettings");
        }
    }
}

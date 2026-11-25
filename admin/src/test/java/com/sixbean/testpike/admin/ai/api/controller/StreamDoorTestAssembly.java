package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.aichat.app.component.SseWindowSettings;
import com.sixbean.testpike.admin.ai.app.service.ExtractionEventStreamBridge;
import com.sixbean.testpike.admin.ai.app.service.StepsGenEventStreamBridge;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiBatchClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiScenarioPreviewClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiTaskClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;

import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Shared wiring for the stream-door controller tests: one holder carries the
 * transport mocks, the wired controllers and the login posture, so door-level
 * tests stop mirroring identical preamble blocks (single-source rule for test
 * fixtures — a constructor change lands here once, not in every test file).
 */
final class StreamDoorTestAssembly {

    final CoreAiTaskClient taskClient = mock(CoreAiTaskClient.class);
    final CoreAiBatchClient batchClient = mock(CoreAiBatchClient.class);
    final CoreAiScenarioPreviewClient previewClient =
            mock(CoreAiScenarioPreviewClient.class);
    final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    final IdempotentGuard idempotentGuard = mock(IdempotentGuard.class);
    final StepsGenEventStreamBridge stepsBridge =
            mock(StepsGenEventStreamBridge.class);
    final AiController aiController = aiController(
            taskClient, batchClient, previewClient, guard, idempotentGuard, stepsBridge);

    final CoreDocumentClient coreDocumentClient = mock(CoreDocumentClient.class);
    final ExtractionEventStreamBridge extractBridge =
            mock(ExtractionEventStreamBridge.class);
    final DocumentController documentController = documentController(
            coreDocumentClient, guard, extractBridge);

    /** The shipped legacy window (30 minutes) used by door-level unit tests. */
    static SseWindowSettings legacyWindow() {
        return new SseWindowSettings(30L * 60L * 1000L);
    }

    static AiController aiController(CoreAiTaskClient taskClient, CoreAiBatchClient batchClient,
                                     CoreAiScenarioPreviewClient previewClient,
                                     ProjectAccessGuard guard, IdempotentGuard idempotentGuard,
                                     StepsGenEventStreamBridge stepsBridge) {
        return new AiController(taskClient, batchClient, previewClient, guard,
                idempotentGuard, stepsBridge, legacyWindow());
    }

    static DocumentController documentController(CoreDocumentClient coreDocumentClient,
                                                 ProjectAccessGuard guard,
                                                 ExtractionEventStreamBridge eventStreamBridge) {
        return new DocumentController(coreDocumentClient, guard, eventStreamBridge, legacyWindow());
    }

    /** Login as a plain project member (the guard mock decides the verdict). */
    void loginMember() {
        UserContext.set(new CurrentUser(5L, "tester", "T", "项目成员", List.of("MEMBER"),
                System.currentTimeMillis() + 3600_000, null));
    }

    void clearLogin() {
        UserContext.clear();
    }
}

package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.BatchReviewRequest;
import com.sixbean.testpike.admin.cases.api.dto.CaseReviewLogView;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.api.dto.ReviewCaseRequest;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ReviewFeedbackBridgeTest {

    private final CoreCaseClient coreCaseClient = mock(CoreCaseClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient coreRoleClient =
            mock(com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient.class);
    private final com.sixbean.testpike.admin.tags.infrastructure.remote.client.CoreTagClient coreTagClient =
            mock(com.sixbean.testpike.admin.tags.infrastructure.remote.client.CoreTagClient.class);
    private final com.sixbean.testpike.admin.requirement.infrastructure.remote.client.CoreRequirementClient
            coreRequirementClient =
            mock(com.sixbean.testpike.admin.requirement.infrastructure.remote.client.CoreRequirementClient.class);
    private final IdempotentGuard idempotentGuard = mock(IdempotentGuard.class);

    private final CaseController controller = new CaseController(coreCaseClient, guard,
            coreRoleClient, coreTagClient, coreRequirementClient, idempotentGuard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

    private void givenCase(long projectId) {
        when(coreCaseClient.getCase(101L)).thenReturn(new CaseView(101L, "TC-1", projectId,
                "标题", null, null, 2, 0, "WEB", 3, null, 5, null, null,
                1, null, 7L, null, null, List.of(), List.of(), 0, null, null, null, 0, null, null, null, null));
    }

    
    @Test
    void reviewCaseRequestCarriesFeedbackTypes() {
        RecordComponent[] components = ReviewCaseRequest.class.getRecordComponents();
        RecordComponent last = components[components.length - 1];
        assertEquals("feedbackTypes", last.getName());
        assertEquals(List.class, last.getType());
        ReviewCaseRequest req = new ReviewCaseRequest("reject", "口径不对", 3,
                List.of("step_missing", "expected_wrong"));
        assertEquals(List.of("step_missing", "expected_wrong"), req.feedbackTypes());
    }

    
    @Test
    void batchReviewRequestCarriesFeedbackTypes() {
        RecordComponent[] components = BatchReviewRequest.class.getRecordComponents();
        RecordComponent last = components[components.length - 1];
        assertEquals("feedbackTypes", last.getName());
        assertEquals(List.class, last.getType());
    }

    
    @Test
    void reviewPassesTypesThroughToCoreClient() {
        login();
        givenCase(11L);
        ReviewCaseRequest request = new ReviewCaseRequest("need_change", "补断言", 4,
                List.of("data_wrong"));

        controller.review(101L, null, request);

        ArgumentCaptor<ReviewCaseRequest> captor = ArgumentCaptor.forClass(ReviewCaseRequest.class);
        verify(coreCaseClient).reviewCase(eq(101L), captor.capture());
        assertEquals(List.of("data_wrong"), captor.getValue().feedbackTypes());
    }

    
    @Test
    void batchReviewPassesTypesAsTrailingArg() {
        login();
        givenCase(11L);
        controller.batchReview(new BatchReviewRequest("reject", List.of(101L), "整批口径错", true,
                List.of("step_missing", "data_wrong")));
        verify(coreCaseClient).batchReviewCases(eq(11L), eq("reject"), eq("整批口径错"),
                eq(List.of(101L)), eq(true), eq(List.of("step_missing", "data_wrong")));
    }

    
    @Test
    void reviewLogViewCarriesFeedbackTypes() {
        RecordComponent[] components = CaseReviewLogView.class.getRecordComponents();
        RecordComponent last = components[components.length - 1];
        assertEquals("feedbackTypes", last.getName());
        assertEquals(List.class, last.getType());
        CaseReviewLogView view = new CaseReviewLogView(1L, 101L, 7L, "张三", "reject", "口径不对",
                java.time.LocalDateTime.now(), List.of("expected_wrong"));
        assertNotNull(view.feedbackTypes());
        assertEquals(List.of("expected_wrong"), view.feedbackTypes());
    }
}

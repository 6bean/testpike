package com.sixbean.testpike.core.deadletter.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterBrowseQuery;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterClassCount;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerRow;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterQueueCount;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterReplayReceipt;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerQueryService;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterReplayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dead-letter internal door contract test (AJ batch second cut, admin-side
 * composition consumes exactly these three shapes).
 * <p>The controller is a thin wrapper per the service-layer rule: query results are
 * forwarded verbatim, filter literals reach the query service untouched (defaults and
 * fail-closed validation live in the service — no second judgement at the api layer),
 * and only GET mappings exist because this door is read-only.
 */
class DeadLetterLedgerInternalControllerTest {

    private final DeadLetterLedgerQueryService queryService = mock(DeadLetterLedgerQueryService.class);
    private final DeadLetterReplayService replayService = mock(DeadLetterReplayService.class);
    private final DeadLetterLedgerInternalController controller =
            new DeadLetterLedgerInternalController(queryService, replayService);

    @BeforeEach
    void loginOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(OperatorContext.OPERATOR_ID_HEADER, "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void queueBacklogForwardsStatusVerbatimIncludingNullDefault() {
        List<DeadLetterQueueCount> rows =
                List.of(new DeadLetterQueueCount("ai-gen.dlq", 7));
        when(queryService.queueBacklog(null)).thenReturn(rows);

        Response<List<DeadLetterQueueCount>> resp = controller.queueBacklog(null);

        assertSame(rows, resp.getData(), "group counts forwarded verbatim (no admin-side recount)");
        verify(queryService).queueBacklog(isNull());
    }

    @Test
    void classDistributionForwardsQueueAndStatusScope() {
        List<DeadLetterClassCount> dist = List.of(new DeadLetterClassCount("TIMEOUT", 3));
        when(queryService.classDistribution("ai-gen.dlq", "pending")).thenReturn(dist);

        Response<List<DeadLetterClassCount>> resp =
                controller.classDistribution("ai-gen.dlq", "pending");

        assertSame(dist, resp.getData());
        verify(queryService).classDistribution(eq("ai-gen.dlq"), eq("pending"));
    }

    @Test
    void browseBuildsQueryWithPagingDefaultsAndForwardsFilters() {
        PageResult<DeadLetterLedgerRow> page = new PageResult<>(List.of(), 0);
        when(queryService.browse(any())).thenReturn(page);

        Response<PageResult<DeadLetterLedgerRow>> resp =
                controller.browse("ai-gen.dlq", "TIMEOUT", null, null, null);

        assertSame(page, resp.getData());
        var captor = org.mockito.ArgumentCaptor.forClass(DeadLetterBrowseQuery.class);
        verify(queryService).browse(captor.capture());
        DeadLetterBrowseQuery built = captor.getValue();
        assertEquals("ai-gen.dlq", built.queueName());
        assertEquals("TIMEOUT", built.failureClass());
        assertEquals(1, built.page(), "absent page defaults to the first page");
        assertEquals(20, built.size(), "absent size defaults to 20 (shared PageQueryPolicy bound)");
    }

    @Test
    void browseForwardsExplicitPagingWithoutClampingHere() {
        when(queryService.browse(any())).thenReturn(new PageResult<>(List.of(), 0));

        controller.browse(null, null, "voided", 3, 50);

        var captor = org.mockito.ArgumentCaptor.forClass(DeadLetterBrowseQuery.class);
        verify(queryService).browse(captor.capture());
        assertEquals(3, captor.getValue().page());
        assertEquals(50, captor.getValue().size());
        assertEquals("voided", captor.getValue().status());
    }

    @Test
    void replayOneForwardsIdConfirmFlagAndHeaderOperator() {
        DeadLetterReplayReceipt receipt = new DeadLetterReplayReceipt(
                7L, "fp-1", "ai-gen.dlq", "testpike.events", "ai.doc.extract", 2);
        when(replayService.replayOne(7L, false, "42")).thenReturn(receipt);

        Response<DeadLetterReplayReceipt> resp = controller.replayOne(7L, false);

        assertSame(receipt, resp.getData(), "receipt forwarded verbatim (no api-layer rewriting)");
        verify(replayService).replayOne(7L, false, "42");
    }

    @Test
    void replayOneWithoutOperatorHeaderForwardsNullForTheServiceToReject() {
        RequestContextHolder.resetRequestAttributes();
        when(replayService.replayOne(anyLong(), anyBoolean(), isNull()))
                .thenThrow(new ValidationException("操作人身份缺失"));

        assertThrows(ValidationException.class, () -> controller.replayOne(7L, true));
        // the guard message stays single-source in the replay service, not re-coded here
        verify(replayService).replayOne(7L, true, null);
    }

    @Test
    void replayByClassParsesVocabularyLiteralIntoTheSingleSourceEnum() {
        when(replayService.replayByFailureClass("ai-gen.dlq",
                DeadLetterFailureClass.LLM_TIMEOUT, true, "42")).thenReturn(3);

        Response<Integer> resp = controller.replayByClass("ai-gen.dlq", "LLM_TIMEOUT", true);

        assertEquals(3, resp.getData(), "reposted count forwarded as counted by the primitive");
    }

    @Test
    void replayByClassRejectsUnknownClassLiteralBeforeTouchingTheService() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> controller.replayByClass("ai-gen.dlq", "NOT_A_CLASS", false));
        assertTrue(ex.getMessage().contains("NOT_A_CLASS"), "rejection names the bad literal");
        verifyNoInteractions(replayService);
    }

    @Test
    void replayQueueForwardsScopeAndConfirmationFlag() {
        when(replayService.replayQueue("ai-gen.dlq", false, "42")).thenReturn(5);

        assertEquals(5, controller.replayQueue("ai-gen.dlq", false).getData());
    }

    @Test
    void voidDoorForwardsIdReasonAndOperator() {
        when(replayService.voidLedger(9L, "实体引用无法修复，按台账口径作废", "42")).thenReturn(true);

        assertTrue(controller.voidLedger(9L, "实体引用无法修复，按台账口径作废").getData());
    }

    @Test
    void doorShapesThreeReadGetsAndFourActionPosts() {
        int gets = 0;
        int posts = 0;
        for (var m : DeadLetterLedgerInternalController.class.getDeclaredMethods()) {
            for (var ann : m.getAnnotations()) {
                String name = ann.annotationType().getSimpleName();
                if ("GetMapping".equals(name)) {
                    gets++;
                } else if ("PostMapping".equals(name)) {
                    posts++;
                }
            }
        }
        assertEquals(3, gets, "the three atomic queries keep their GET shape");
        assertEquals(4, posts, "replay one / by class / queue + void are the four POST action doors");
    }
}
